/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.NotNull

public open class QuickFixWithDelegateFactory(
        private val delegateFactory: () -> IntentionAction
) : IntentionAction {
    private val familyName: String
    private val text: String
    private val startInWriteAction: Boolean

    init {
        val delegate = delegateFactory()
        familyName = delegate.familyName
        text = delegate.text
        startInWriteAction = delegate.startInWriteAction()
    }

    override fun getFamilyName() = familyName

    override fun getText() = text

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile) =
            delegateFactory().isAvailable(project, editor, file)

    override fun startInWriteAction() = startInWriteAction

    override fun invoke(NotNull project: Project, editor: Editor?, file: PsiFile?) {
        delegateFactory().invoke(project, editor, file)
    }
}

public class LowPriorityQuickFixWithDelegateFactory(
        delegateFactory: () -> IntentionAction
): QuickFixWithDelegateFactory(delegateFactory), LowPriorityAction