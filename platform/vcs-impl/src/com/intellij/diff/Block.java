/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diff;

import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.util.diff.Diff;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * author: lesya
 */
public class Block {
  private final String[] mySource;
  private final int myStart;
  private final int myEnd;

  public Block(@NotNull String source, int start, int end) {
    this(LineTokenizer.tokenize(source.toCharArray(), false), start, end);
  }

  public Block(@NotNull String[] source, int start, int end) {
    mySource = source;
    myStart = start;
    myEnd = end;
  }

  @NotNull
  public Block createPreviousBlock(@NotNull String prevContent) {
    return createPreviousBlock(LineTokenizer.tokenize(prevContent.toCharArray(), false));
  }

  @NotNull
  public Block createPreviousBlock(@NotNull String[] prevContent) {
    int startLine = myStart;
    int endLine = myEnd;

    Diff.Change change = Diff.buildChangesSomehow(prevContent, getSource());
    while (change != null) {
      int startLine1 = change.line0;
      int startLine2 = change.line1;
      int endLine1 = startLine1 + change.deleted;
      int endLine2 = startLine2 + change.inserted;

      int shiftStart = startLine2 - startLine1;
      int shiftEnd = endLine2 - endLine1;

      if (startLine2 <= myStart) {
        startLine = myStart - shiftStart;
      }

      if (endLine2 <= myStart) {
        startLine = myStart - shiftEnd;
      }

      if (startLine2 < myEnd) {
        endLine = myEnd - shiftEnd;
      }

      change = change.link;
    }

    if (endLine > prevContent.length) {
      endLine = prevContent.length;
    }
    if (startLine < 0) startLine = 0;
    if (endLine < startLine) endLine = startLine;

    return new Block(prevContent, startLine, endLine);
  }

  @NotNull
  public String getBlockContent() {
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < myEnd - myStart; i++) {
      if (i != 0) result.append("\n");
      result.append(mySource[i + myStart]);
    }

    return result.toString();
  }

  public int hashCode() {
    return Arrays.hashCode(mySource) ^ myStart ^ myEnd;
  }

  public boolean equals(Object object) {
    if (!(object instanceof Block)) return false;
    Block other = (Block)object;
    return Arrays.equals(mySource, other.mySource)
           && myStart == other.myStart
           && myEnd == other.myEnd;
  }

  public int getStart() {
    return myStart;
  }

  public int getEnd() {
    return myEnd;
  }

  public String[] getSource() {
    return mySource;
  }

  public String toString() {
    StringBuilder result = new StringBuilder();

    appendLines(result, 0, myStart);

    result.append("<-----------------------------\n");

    appendLines(result, myStart, myEnd);

    result.append("----------------------------->\n");

    appendLines(result, myEnd, mySource.length);

    return result.toString();
  }

  private void appendLines(StringBuilder result, int from, int to) {
    for (int i = from; i < to; i++) {
      result.append(mySource[i]);
      result.append("\n");
    }
  }
}
