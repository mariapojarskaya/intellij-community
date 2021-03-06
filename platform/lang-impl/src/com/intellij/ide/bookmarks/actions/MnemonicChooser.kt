// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.bookmarks.actions

import com.intellij.ide.bookmarks.BookmarkBundle
import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.ide.bookmarks.BookmarkType
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.JBColor.namedColor
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.RowGridLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.RegionPaintIcon
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import org.jetbrains.annotations.Nls
import java.awt.*
import java.awt.RenderingHints.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.lang.ref.WeakReference
import javax.swing.*

private val ASSIGNED_BACKGROUND = namedColor("AssignedMnemonic.background", 0xF7C777, 0x665632)
private val ASSIGNED_FOREGROUND = namedColor("AssignedMnemonic.foreground", 0x000000, 0xBBBBBB)
private val CURRENT_BACKGROUND = namedColor("AssignedMnemonic.selectionBackground", 0x3875D6, 0x2F65CA)
private val CURRENT_FOREGROUND = namedColor("AssignedMnemonic.selectionForeground", 0xFFFFFF, 0xFFFFFF)

private val SHARED_CURSOR by lazy { Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
private val SHARED_LAYOUT by lazy {
  object : RowGridLayout(0, 4, 2, SwingConstants.CENTER) {
    override fun getCellSize(sizes: List<Dimension>) = Dimension(JBUI.scale(24), JBUI.scale(28))
  }
}

internal abstract class MnemonicChooser(
  private val manager: BookmarkManager,
  private val current: BookmarkType
) : BorderLayoutPanel(), KeyListener {

  var reference: WeakReference<JBPopup>? = null

  init {
    isFocusCycleRoot = true
    focusTraversalPolicy = LayoutFocusTraversalPolicy()
    addToLeft(createButtons { it.mnemonic.isDigit() })
    addToRight(createButtons { it.mnemonic.isLetter() })
    if (manager.hasBookmarksWithMnemonics()) {
      addToBottom(BorderLayoutPanel().apply {
        border = JBUI.Borders.empty(0, 10)
        addToTop(JSeparator())
        addToBottom(JPanel(HorizontalLayout(5)).apply {
          border = JBUI.Borders.empty(5, 0)
          add(HorizontalLayout.LEFT, createLegend(ASSIGNED_BACKGROUND, BookmarkBundle.message("mnemonic.chooser.legend.assigned.bookmark")))
          if (current != BookmarkType.DEFAULT) {
            add(HorizontalLayout.LEFT, createLegend(CURRENT_BACKGROUND, BookmarkBundle.message("mnemonic.chooser.legend.current.bookmark")))
          }
        })
      })
    }
  }

  private fun buttons() = UIUtil.uiTraverser(this).traverse().filter(JButton::class.java)

  fun createPopup(cancelKey: Boolean) = JBPopupFactory.getInstance().createComponentPopupBuilder(this, buttons().first())
    .setTitle(BookmarkBundle.message("popup.title.bookmark.mnemonic"))
    .setCancelKeyEnabled(cancelKey)
    .setFocusable(true)
    .setRequestFocus(true)
    .setMovable(false)
    .setResizable(false)
    .createPopup()
    .also { reference = WeakReference(it) }

  protected open fun onChosen(type: BookmarkType) = onCancelled()
  protected open fun onCancelled() {
    reference?.get()?.cancel()
    reference = null
  }

  private fun createButtons(predicate: (BookmarkType) -> Boolean) = JPanel(SHARED_LAYOUT).apply {
    border = JBUI.Borders.empty(5)
    BookmarkType.values().filter(predicate).forEach { add(createButton(it)) }
  }

  private fun createButton(type: BookmarkType) = JButton(type.mnemonic.toString()).apply {
    setMnemonic(type.mnemonic)
    addActionListener { onChosen(type) }
    putClientProperty("ActionToolbar.smallVariant", true)
    when {
      type == current -> {
        putClientProperty("JButton.textColor", CURRENT_FOREGROUND)
        putClientProperty("JButton.backgroundColor", CURRENT_BACKGROUND)
      }
      manager.findBookmarkForMnemonic(type.mnemonic) != null -> {
        putClientProperty("JButton.textColor", ASSIGNED_FOREGROUND)
        putClientProperty("JButton.backgroundColor", ASSIGNED_BACKGROUND)
      }
    }
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true), "released")
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, false), "pressed")
    cursor = SHARED_CURSOR
  }.also {
    it.addKeyListener(this)
  }

  private fun createLegend(color: Color, @Nls text: String) = JLabel(text).apply {
    icon = RegionPaintIcon(8) { g, x, y, width, height, _ ->
      g.color = color
      g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)
      g.fillOval(x, y, width, height)
    }.withIconPreScaled(false)
  }

  private fun offset(delta: Int, size: Int) = when {
    delta < 0 -> delta
    delta > 0 -> delta + size
    else -> size / 2
  }

  private fun next(source: Component, dx: Int, dy: Int): Component? {
    val point = SwingUtilities.convertPoint(source, offset(dx, source.width), offset(dy, source.height), this)
    val component = next(source, dx, dy, point)
    if (component != null || !Registry.`is`("ide.bookmark.mnemonic.chooser.cyclic.scrolling.allowed")) return component
    if (dx > 0) point.x = 0
    if (dx < 0) point.x = dx + width
    if (dy > 0) point.y = 0
    if (dy < 0) point.y = dy + height
    return next(source, dx, dy, point)
  }

  private fun next(source: Component, dx: Int, dy: Int, point: Point): Component? {
    while (contains(point)) {
      val component = SwingUtilities.getDeepestComponentAt(this, point.x, point.y)
      if (component is JButton) return component
      point.translate(dx * source.width / 2, dy * source.height / 2)
    }
    return null
  }

  override fun keyTyped(event: KeyEvent) = Unit
  override fun keyReleased(event: KeyEvent) = Unit
  override fun keyPressed(event: KeyEvent) {
    if (event.modifiersEx == 0) {
      when (event.keyCode) {
        KeyEvent.VK_ESCAPE -> onCancelled()
        KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> next(event.component, 0, -1)?.requestFocus()
        KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> next(event.component, 0, 1)?.requestFocus()
        KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> next(event.component, -1, 0)?.requestFocus()
        KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> next(event.component, 1, 0)?.requestFocus()
        else -> {
          val type = BookmarkType.get(event.keyCode.toChar())
          if (type != BookmarkType.DEFAULT) onChosen(type)
        }
      }
    }
  }
}
