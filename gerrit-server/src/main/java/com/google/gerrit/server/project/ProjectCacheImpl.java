// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.project;

import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.RefRight;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.cache.Cache;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.cache.EntryCreator;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gwtorm.client.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Cache of project information, including access rights. */
@Singleton
public class ProjectCacheImpl implements ProjectCache {
  private static final String CACHE_NAME = "projects";
  private static final String CACHE_LIST = "project_list";

  public static Module module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        final TypeLiteral<Cache<Project.NameKey, ProjectState>> nameType =
            new TypeLiteral<Cache<Project.NameKey, ProjectState>>() {};
        core(nameType, CACHE_NAME).populateWith(Loader.class);

        final TypeLiteral<Cache<ListKey, SortedSet<Project.NameKey>>> listType =
            new TypeLiteral<Cache<ListKey, SortedSet<Project.NameKey>>>() {};
        core(listType, CACHE_LIST).populateWith(Lister.class);

        bind(ProjectCacheImpl.class);
        bind(ProjectCache.class).to(ProjectCacheImpl.class);
      }
    };
  }

  private final Cache<Project.NameKey, ProjectState> byName;
  private final Cache<ListKey,SortedSet<Project.NameKey>> list;
  private final Lock listLock;

  @Inject
  ProjectCacheImpl(
      @Named(CACHE_NAME) final Cache<Project.NameKey, ProjectState> byName,
      @Named(CACHE_LIST) final Cache<ListKey, SortedSet<Project.NameKey>> list) {
    this.byName = byName;
    this.list = list;
    this.listLock = new ReentrantLock(true /* fair */);
  }

  /**
   * Get the cached data for a project by its unique name.
   *
   * @param projectName name of the project.
   * @return the cached data; null if no such project exists.
   */
  public ProjectState get(final Project.NameKey projectName) {
    return byName.get(projectName);
  }

  /** Invalidate the cached information about the given project. */
  public void evict(final Project p) {
    if (p != null) {
      byName.remove(p.getNameKey());
    }
  }

  /** Invalidate the cached information about all projects. */
  public void evictAll() {
    byName.removeAll();
  }

  @Override
  public void onCreateProject(Project.NameKey newProjectName) {
    listLock.lock();
    try {
      SortedSet<Project.NameKey> n = list.get(ListKey.ALL);
      n = new TreeSet<Project.NameKey>(n);
      n.add(newProjectName);
      list.put(ListKey.ALL, Collections.unmodifiableSortedSet(n));
    } finally {
      listLock.unlock();
    }
  }

  @Override
  public Iterable<Project.NameKey> all() {
    return list.get(ListKey.ALL);
  }

  @Override
  public Iterable<Project.NameKey> byName(final String pfx) {
    return new Iterable<Project.NameKey>() {
      @Override
      public Iterator<Project.NameKey> iterator() {
        return new Iterator<Project.NameKey>() {
          private Project.NameKey next;
          private Iterator<Project.NameKey> itr =
              list.get(ListKey.ALL).tailSet(new Project.NameKey(pfx)).iterator();

          @Override
          public boolean hasNext() {
            if (next != null) {
              return true;
            }

            if (!itr.hasNext()) {
              return false;
            }

            Project.NameKey r = itr.next();
            if (r.get().startsWith(pfx)) {
              next = r;
              return true;
            } else {
              itr = Collections.<Project.NameKey> emptyList().iterator();
              return false;
            }
          }

          @Override
          public Project.NameKey next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }

            Project.NameKey r = next;
            next = null;
            return r;
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  static class Loader extends EntryCreator<Project.NameKey, ProjectState> {
    private final ProjectState.Factory projectStateFactory;
    private final SchemaFactory<ReviewDb> schema;

    @Inject
    Loader(ProjectState.Factory psf, SchemaFactory<ReviewDb> sf) {
      projectStateFactory = psf;
      schema = sf;
    }

    @Override
    public ProjectState createEntry(Project.NameKey key) throws Exception {
      final ReviewDb db = schema.open();
      try {
        final Project p = db.projects().get(key);
        if (p == null) {
          return null;
        }

        final Collection<RefRight> rights =
            Collections.unmodifiableCollection(db.refRights().byProject(
                p.getNameKey()).toList());

        return projectStateFactory.create(p, rights);
      } finally {
        db.close();
      }
    }
  }

  static class ListKey {
    static final ListKey ALL = new ListKey();

    private ListKey() {
    }
  }

  static class Lister extends EntryCreator<ListKey, SortedSet<Project.NameKey>> {
    private final GitRepositoryManager mgr;

    @Inject
    Lister(GitRepositoryManager mgr) {
      this.mgr = mgr;
    }

    @Override
    public SortedSet<Project.NameKey> createEntry(ListKey key) throws Exception {
      return mgr.list();
    }
  }
}
