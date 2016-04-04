/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.psi.impl.search;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchScopeUtil;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.DirectClassInheritorsSearch;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.Stack;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

public class JavaClassInheritorsSearcher extends QueryExecutorBase<PsiClass, ClassInheritorsSearch.SearchParameters> {
  @Override
  public void processQuery(@NotNull ClassInheritorsSearch.SearchParameters parameters, @NotNull Processor<PsiClass> consumer) {
    final PsiClass baseClass = parameters.getClassToProcess();
    assert parameters.isCheckDeep();
    assert parameters.isCheckInheritance();

    ProgressIndicator progress = ProgressIndicatorProvider.getGlobalProgressIndicator();
    if (progress != null) {
      progress.pushState();
      String className = ApplicationManager.getApplication().runReadAction((Computable<String>)baseClass::getName);
      progress.setText(className != null ?
                       PsiBundle.message("psi.search.inheritors.of.class.progress", className) :
                       PsiBundle.message("psi.search.inheritors.progress"));
    }

    try {
      processInheritors(parameters, consumer);
    }
    finally {
      if (progress != null) {
        progress.popState();
      }
    }
  }

  private static boolean processInheritors(@NotNull final ClassInheritorsSearch.SearchParameters parameters,
                                           @NotNull final Processor<PsiClass> consumer) {
    @NotNull final PsiClass baseClass = parameters.getClassToProcess();
    if (baseClass instanceof PsiAnonymousClass || isFinal(baseClass)) return true;

    final SearchScope searchScope = parameters.getScope();
    Project project = PsiUtilCore.getProjectInReadAction(baseClass);
    if (isJavaLangObject(baseClass)) {
      return AllClassesSearch.search(searchScope, project, parameters.getNameCondition()).forEach(aClass -> {
        ProgressManager.checkCanceled();
        return isJavaLangObject(aClass) || consumer.process(aClass);
      });
    }
    if (searchScope instanceof LocalSearchScope) {
      return processLocalScope(project, parameters, (LocalSearchScope)searchScope, baseClass, consumer);
    }

    Collection<PsiClass> cached = getOrComputeSubClasses(project, parameters.getClassToProcess());

    for (final PsiClass subClass : cached) {
      ProgressManager.checkCanceled();
      if (subClass instanceof PsiAnonymousClass && !parameters.isIncludeAnonymous()) {
        continue;
      }
      if (ApplicationManager.getApplication().runReadAction((Computable<Boolean>)() ->
        checkCandidate(subClass, parameters) && !consumer.process(subClass))) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private static Collection<PsiClass> getOrComputeSubClasses(@NotNull Project project, @NotNull PsiClass baseClass) {
    ConcurrentMap<PsiClass, Collection<PsiClass>> CACHE = HighlightingCaches.getInstance(project).ALL_SUB_CLASSES;
    Collection<PsiClass> cached = CACHE.get(baseClass);
    if (cached == null) {
      cached = computeAllSubClasses(project, baseClass); // it's almost empty now, no big deal
      // make sure concurrent calls of this method always return the same collection to avoid expensive duplicate work
      cached = ConcurrencyUtil.cacheOrGet(CACHE, baseClass, cached);
    }
    return cached;
  }

  @NotNull
  // returns lazy collection of subclasses. Each call to next() leads to calculation of next batch of subclasses.
  // Candidates to subclasses are kept in 'stack'. Already computed inheritors are in 'subClasses' array.
  private static Collection<PsiClass> computeAllSubClasses(@NotNull Project project, @NotNull PsiClass baseClass) {
    final Stack<PsiAnchor> stack = new Stack<>(); // guarded by lock
    final Set<PsiAnchor> processed = new THashSet<>(); // guarded by lock
    final List<PsiClass> subClasses = Collections.synchronizedList(new ArrayList<>());
    final Object lock = new Object();
    final GlobalSearchScope projectScope = GlobalSearchScope.allScope(project);

    ApplicationManager.getApplication().runReadAction(() -> {
      stack.push(PsiAnchor.create(baseClass));
    });
    return new AbstractCollection<PsiClass>() {
      {
        checkNextCandidates(); // populate with at least one subclass
      }

      @NotNull
      @Override
      public Iterator<PsiClass> iterator() {
        return new Iterator<PsiClass>() {
          int index;

          @Override
          public boolean hasNext() {
            if (index < subClasses.size()) return true;
            checkNextCandidates();
            return index < subClasses.size();
          }

          @Override
          public PsiClass next() {
            return subClasses.get(index++);
          }
        };
      }

      @Override
      public int size() {
        throw new UnsupportedOperationException();
      }

      private void checkNextCandidates() {
        // This collection can be iterated from different threads concurrently,
        // so taking element off the stack and adding checked candidates to subClasses should be atomic.
        // More, it should happen under read action because otherwise the deadlock is possible because checkNextCandidates() could be called with or without read action.
        ApplicationManager.getApplication().runReadAction(() -> {
          synchronized (lock) {
            while (!stack.isEmpty()) {
              ProgressManager.checkCanceled();

              final PsiAnchor anchor = stack.pop();
              if (!processed.add(anchor)) continue;

              PsiClass psiClass = (PsiClass)anchor.retrieve();
              if (psiClass == null) continue;

              if (!(psiClass instanceof PsiAnonymousClass) && !psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                DirectClassInheritorsSearch.search(psiClass, projectScope).forEach(
                  candidate -> {
                    ProgressManager.checkCanceled();
                    stack.push(PsiAnchor.create(candidate));
                    return true;
                  });
              }

              if (psiClass != baseClass) {
                subClasses.add(psiClass);
                return; // just allow the iterator to move forward, rest elements will be added on the next call to .next()
              }
            }
            processed.clear(); // prevent too many PsiAnchors retained by this anonymous class closure
          }
        });
      }
    };
  }

  private static boolean processLocalScope(@NotNull final Project project,
                                           @NotNull final ClassInheritorsSearch.SearchParameters parameters,
                                           @NotNull LocalSearchScope searchScope,
                                           @NotNull PsiClass baseClass,
                                           @NotNull Processor<PsiClass> consumer) {
    // optimisation: in case of local scope it's considered cheaper to enumerate all scope files and check if there is an inheritor there,
    // instead of traversing the (potentially huge) class hierarchy and filter out almost everything by scope.
    VirtualFile[] virtualFiles = searchScope.getVirtualFiles();

    final boolean[] success = {true};
    for (VirtualFile virtualFile : virtualFiles) {
      ProgressManager.checkCanceled();
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
          if (psiFile != null) {
            psiFile.accept(new JavaRecursiveElementVisitor() {
              @Override
              public void visitClass(PsiClass candidate) {
                ProgressManager.checkCanceled();
                if (!success[0]) return;
                if (candidate.isInheritor(baseClass, true)
                    && checkCandidate(candidate, parameters)
                    && !consumer.process(candidate)) {
                  success[0] = false;
                  return;
                }
                super.visitClass(candidate);
              }

              @Override
              public void visitCodeBlock(PsiCodeBlock block) {
                ProgressManager.checkCanceled();
                if (!parameters.isIncludeAnonymous()) return;
                super.visitCodeBlock(block);
              }
            });
          }
        }
      });
    }
    return success[0];
  }

  private static boolean checkCandidate(@NotNull PsiClass candidate, @NotNull ClassInheritorsSearch.SearchParameters parameters) {
    SearchScope searchScope = parameters.getScope();
    ProgressManager.checkCanceled();

    if (!PsiSearchScopeUtil.isInScope(searchScope, candidate)) {
      return false;
    }
    if (candidate instanceof PsiAnonymousClass) {
      return true;
    }

    String name = candidate.getName();
    return name != null && parameters.getNameCondition().value(name);
  }

  static boolean isJavaLangObject(@NotNull final PsiClass baseClass) {
    return ApplicationManager.getApplication().runReadAction(
      (Computable<Boolean>)() -> baseClass.isValid() && CommonClassNames.JAVA_LANG_OBJECT.equals(baseClass.getQualifiedName()));
  }

  private static boolean isFinal(@NotNull final PsiClass baseClass) {
    return ApplicationManager.getApplication().runReadAction((Computable<Boolean>)() -> baseClass.hasModifierProperty(PsiModifier.FINAL));
  }
}
