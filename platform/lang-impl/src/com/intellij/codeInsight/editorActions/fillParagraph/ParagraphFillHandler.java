package com.intellij.codeInsight.editorActions.fillParagraph;

import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.CharFilter;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.source.codeStyle.CodeFormatterFacade;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Defines general re-flow paragraph functionality.
 * Serves plain text files.
 * 
 * User : ktisha
 */
public class ParagraphFillHandler {

  protected void performOnElement(@NotNull final PsiElement element, @NotNull final Editor editor) {
    final Document document = editor.getDocument();

    final TextRange textRange = getTextRange(element, editor);
    final String text = textRange.substring(element.getContainingFile().getText());

    final List<String> subStrings = StringUtil.split(text, "\n", true);
    final StringBuilder stringBuilder = new StringBuilder();
    for (String string : subStrings) {
      stringBuilder.append(StringUtil.trimStart(string.trim(), getPrefix(element))).append(" ");
    }
    final String replacementText = stringBuilder.toString();

    CommandProcessor.getInstance().executeCommand(element.getProject(), new Runnable() {
      public void run() {
        document.replaceString(textRange.getStartOffset(), textRange.getEndOffset(),
                               getPrefix(element) + replacementText);
        final CodeFormatterFacade codeFormatter = new CodeFormatterFacade(
                                        CodeStyleSettingsManager.getSettings(element.getProject()));
        codeFormatter.doWrapLongLinesIfNecessary(editor, element.getProject(), document,
                                                 textRange.getStartOffset(),
                                                 textRange.getStartOffset() + replacementText.length() + 1);
      }
    }, null, document);

  }

  private TextRange getTextRange(@NotNull final PsiElement element, @NotNull final Editor editor) {
    int startOffset = getStartOffset(element, editor);
    int endOffset = getEndOffset(element, editor);
    return TextRange.create(startOffset, endOffset);
  }

  private int getStartOffset(@NotNull final PsiElement element, @NotNull final Editor editor) {
    if (element instanceof PsiComment) {
      final PsiElement firstCommentElement = getFirstCommentElement(element);
      return firstCommentElement != null? firstCommentElement.getTextRange().getStartOffset()
                                        : element.getTextRange().getStartOffset();
    }
    final int offset = editor.getCaretModel().getOffset();
    final int elementTextOffset = element.getTextOffset();
    final Document document = editor.getDocument();
    int lineNumber = document.getLineNumber(offset);

    while (lineNumber != document.getLineNumber(elementTextOffset)) {
      final String text = document.getText(TextRange.create(document.getLineStartOffset(lineNumber),
                                                            document.getLineEndOffset(lineNumber)));
      if (StringUtil.isEmptyOrSpaces(text)) {
        break;
      }
      lineNumber -= 1;
    }
    final int lineStartOffset = document.getLineStartOffset(lineNumber + 1);
    final String lineText = document
      .getText(TextRange.create(lineStartOffset, document.getLineEndOffset(lineNumber + 1)));
    int shift = StringUtil.findFirst(lineText, CharFilter.NOT_WHITESPACE_FILTER);

    return lineStartOffset + shift;
  }

  private int getEndOffset(@NotNull final PsiElement element, @NotNull final Editor editor) {
    if (element instanceof PsiComment) {
      final PsiElement next = getLastCommentElement(element);
      return next != null? next.getTextRange().getEndOffset()
                         : element.getTextRange().getEndOffset();
    }
    final int offset = editor.getCaretModel().getOffset();
    final int elementTextOffset = element.getTextRange().getEndOffset();
    final Document document = editor.getDocument();
    int lineNumber = document.getLineNumber(offset);

    while (lineNumber != document.getLineNumber(elementTextOffset)) {
      final String text = document.getText(TextRange.create(document.getLineStartOffset(lineNumber),
                                                            document.getLineEndOffset(lineNumber)));
      if (StringUtil.isEmptyOrSpaces(text))
        break;
      lineNumber += 1;
    }
    return document.getLineEndOffset(lineNumber - 1);
  }

  @Nullable
  private PsiElement getFirstCommentElement(@NotNull final PsiElement element) {
    PsiElement prevSibling = element.getPrevSibling();
    PsiElement result = element;
    while (prevSibling instanceof PsiComment || (prevSibling instanceof PsiWhiteSpace &&
                                                 StringUtil.countChars(prevSibling.getText(), '\n') <= 1)) {
      final String text = prevSibling.getText();
      if (prevSibling instanceof PsiComment && StringUtil.isEmptyOrSpaces(
        StringUtil.trimStart(text.trim(), getPrefix(element)))) {
        break;
      }
      if (prevSibling instanceof PsiComment)
        result = prevSibling;
      prevSibling = prevSibling.getPrevSibling();
    }
    return result;
  }

  @Nullable
  private PsiElement getLastCommentElement(@NotNull final PsiElement element) {
    PsiElement nextSibling = element.getNextSibling();
    PsiElement result = element;
    while (nextSibling instanceof PsiComment || (nextSibling instanceof PsiWhiteSpace &&
                                                 StringUtil.countChars(nextSibling.getText(), '\n') <= 1)) {
      final String text = nextSibling.getText();
      if (nextSibling instanceof PsiComment && StringUtil.isEmptyOrSpaces(
        StringUtil.trimStart(text.trim(), getPrefix(element)))) {
        break;
      }
      if (nextSibling instanceof PsiComment)
        result = nextSibling;
      nextSibling = nextSibling.getNextSibling();
    }
    return result;
  }

  protected boolean isAvailableForElement(@Nullable final PsiElement element) {
    return element != null;
  }

  protected boolean isAvailableForFile(@Nullable final PsiFile psiFile) {
    return psiFile instanceof PsiPlainTextFile;
  }

  @NotNull
  protected String getPrefix(@NotNull final PsiElement element) {
    return "";
  }

}
