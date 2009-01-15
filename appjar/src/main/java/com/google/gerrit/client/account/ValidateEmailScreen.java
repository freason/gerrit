// Copyright 2009 Google Inc.
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

package com.google.gerrit.client.account;

import com.google.gerrit.client.Link;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.ui.AccountScreen;
import com.google.gwt.user.client.History;
import com.google.gwtjsonrpc.client.VoidResult;

public class ValidateEmailScreen extends AccountScreen {
  private final String magicToken;

  public ValidateEmailScreen(final String magicToken) {
    super(Util.C.accountSettingsHeading());
    this.magicToken = magicToken;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    Util.ACCOUNT_SEC.validateEmail(magicToken,
        new GerritCallback<VoidResult>() {
          public void onSuccess(final VoidResult result) {
            History.newItem(Link.SETTINGS_CONTACT, true);
          }
        });
  }
}
