// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.viewModel.definition;


import javax.swing.*;

public class ToggleIconAction extends IconAction {
  boolean myState;

  public ToggleIconAction(Icon icon, String tooltipText, Runnable action, boolean state) {
    super(icon, tooltipText, action);
    myState = state;
  }

  public boolean getState() {
    return myState;
  }
}
