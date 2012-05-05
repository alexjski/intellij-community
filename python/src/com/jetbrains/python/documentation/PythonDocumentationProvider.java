package com.jetbrains.python.documentation;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.ExternalDocumentationProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Function;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.PyCodeInsightSettings;
import com.jetbrains.python.console.PydevConsoleRunner;
import com.jetbrains.python.console.PydevDocumentationProvider;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import com.jetbrains.python.psi.types.*;
import com.jetbrains.python.toolbox.ChainIterable;
import com.jetbrains.python.toolbox.FP;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

import static com.jetbrains.python.documentation.DocumentationBuilderKit.*;

/**
 * Provides quick docs for classes, methods, and functions.
 * Generates documentation stub
 */
public class PythonDocumentationProvider extends AbstractDocumentationProvider implements ExternalDocumentationProvider {

  @NonNls static final String LINK_TYPE_CLASS = "#class#";
  @NonNls static final String LINK_TYPE_PARENT = "#parent#";
  @NonNls static final String LINK_TYPE_PARAM = "#param#";

  @NonNls private static final String RST_PREFIX = ":";
  @NonNls private static final String EPYDOC_PREFIX = "@";

  @NonNls private static final String UNKNOWN = "unknown";

  // provides ctrl+hover info
  public String getQuickNavigateInfo(final PsiElement element, PsiElement originalElement) {
    if (element instanceof PyFunction) {
      PyFunction func = (PyFunction)element;
      StringBuilder cat = new StringBuilder();
      PyClass cls = func.getContainingClass();
      if (cls != null) {
        String cls_name = cls.getName();
        cat.append("class ").append(cls_name).append("\n");
        // It would be nice to have class import info here, but we don't know the ctrl+hovered reference and context
      }
      return $(cat.toString()).add(describeDecorators(func, LSame2, ", ", LSame1)).add(describeFunction(func, LSame2, LSame1)).toString();
    }
    else if (element instanceof PyClass) {
      PyClass cls = (PyClass)element;
      return describeDecorators(cls, LSame2, ", ", LSame1).add(describeClass(cls, LSame2, false, false)).toString();
    }
    else if (element instanceof PyTargetExpression || element instanceof PyNamedParameter) {
      return describeExpression((PyExpression)element, originalElement);
    }
    return null;
  }

  /**
   * Creates a HTML description of function definition.
   * @param fun the function
   * @param func_name_wrapper puts a tag around the function name
   * @param escaper sanitizes values that come directly from doc string or code
   * @return chain of strings for further chaining
   */
  static ChainIterable<String> describeFunction(
    PyFunction fun,
    FP.Lambda1<Iterable<String>, Iterable<String>> func_name_wrapper,
    FP.Lambda1<String, String> escaper
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    final String name = fun.getName();
    cat.addItem("def ").addWith(func_name_wrapper, $(name));
    cat.addItem(escaper.apply(PyUtil.getReadableRepr(fun.getParameterList(), false)));
    if (!PyNames.INIT.equals(name)) {
      cat.addItem(escaper.apply("\nInferred type: "));
      cat.addItem(escaper.apply(getTypeDescription(fun)));
    }
    return cat;
  }

  @Nullable
  private static String describeExpression(PyExpression expr, PsiElement originalElement) {
    final String name = expr.getName();
    if (name != null) {
      StringBuilder result = new StringBuilder((expr instanceof PyNamedParameter) ? "parameter" : "variable");
      result.append(String.format(" \"%s\"", name));
      if (expr instanceof PyNamedParameter) {
        final PyFunction function = PsiTreeUtil.getParentOfType(expr, PyFunction.class);
        if (function != null) {
          result.append(" of ").append(function.getContainingClass() == null ? "function" : "method");
          result.append(String.format(" \"%s\"", function.getName()));
        }
      }
      if (originalElement instanceof PyExpression && originalElement.isValid()) {
        result.append("\n").append(describeExpressionType((PyExpression)originalElement));
      }
      return result.toString();
    }
    return null;
  }

  static String describeExpressionType(PyExpression expr) {
    final TypeEvalContext context = TypeEvalContext.slow();
    return String.format("Inferred type: %s", getTypeName(expr.getType(context), context));
  }

  public static String getTypeDescription(@NotNull PyFunction fun) {
    final TypeEvalContext context = TypeEvalContext.slow();
    final PyType returnType = fun.getReturnType(context, null);
    return String.format("(%s) -> %s\n",
                         StringUtil.join(fun.getParameterList().getParameters(),
                                         new Function<PyParameter, String>() {
                                           @Override
                                           public String fun(PyParameter p) {
                                             final PyNamedParameter np = p.getAsNamed();
                                             if (np != null) {
                                               String name = UNKNOWN;
                                               final PyType t = np.getType(context);
                                               if (t != null) {
                                                 name = getTypeName(t, context);
                                               }
                                               return String.format("%s: %s", np.getName(), name);
                                             }
                                             return p.toString();
                                           }
                                         }, ", "),
                         returnType != null ? getTypeName(returnType, context) : UNKNOWN);
  }

  public static String getTypeName(@Nullable PyType type, @NotNull final TypeEvalContext context) {
    return getTypeName(type, context, new HashMap<PyType, String>());
  }

  private static String getTypeName(@Nullable PyType type, @NotNull final TypeEvalContext context, @NotNull final Map<PyType, String> visited) {
    final String evaluated = visited.get(type);
    if (evaluated != null) {
      return evaluated;
    }
    String result = null;
    final String typeName = type != null ? type.getName() : null;
    if (type instanceof PyTypeReference) {
      final PyType resolved = ((PyTypeReference)type).resolve(null, context);
      if (resolved != null) {
        result = getTypeName(resolved, context, visited);
      }
    }
    else if (type instanceof PyCollectionType) {
      final String name = type.getName();
      final PyType elementType = ((PyCollectionType)type).getElementType(context);
      if (elementType != null) {
        result = String.format("%s of %s", name, getTypeName(elementType, context, visited));
      }
    }
    else if (type instanceof PyUnionType) {
      result = String.format("one of (%s)", StringUtil.join(((PyUnionType)type).getMembers(),
                                                            new Function<PyType, String>() {
                                                              @Override
                                                              public String fun(PyType t) {
                                                                return getTypeName(t, context, visited);
                                                              }
                                                            }, ", "));
    }
    if (result == null) {
      result = typeName != null ? typeName : UNKNOWN;
    }
    visited.put(type, result);
    return result;
  }

  static ChainIterable<String> describeDecorators(
    PyDecoratable what, FP.Lambda1<Iterable<String>, Iterable<String>> deco_name_wrapper,
    String deco_separator, FP.Lambda1<String, String> escaper
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    PyDecoratorList deco_list = what.getDecoratorList();
    if (deco_list != null) {
      for (PyDecorator deco : deco_list.getDecorators()) {
        cat.add(describeDeco(deco, deco_name_wrapper, escaper)).addItem(deco_separator); // can't easily pass describeDeco to map() %)
      }
    }
    return cat;
  }

  /**
   * Creates a HTML description of function definition.
   * @param cls the class
   * @param name_wrapper wrapper to render the name with
   * @param allow_html
   *@param link_own_name if true, add link to class's own name  @return cat for easy chaining
   */
  static ChainIterable<String> describeClass(
    PyClass cls,
    FP.Lambda1<Iterable<String>, Iterable<String>> name_wrapper,
    boolean allow_html, boolean link_own_name
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    final String name = cls.getName();
    cat.addItem("class ");
    if (allow_html && link_own_name) cat.addWith(LinkMyClass, $(name));
    else cat.addWith(name_wrapper, $(name));
    final PyExpression[] ancestors = cls.getSuperClassExpressions();
    if (ancestors.length > 0) {
      cat.addItem("(");
      boolean is_not_first = false;
      for (PyExpression parent : ancestors) {
        final String parentName = parent.getName();
        if (parentName == null) {
          continue;
        }
        if (is_not_first) cat.addItem(", ");
        else is_not_first = true;
        if (allow_html) cat.addWith(new DocumentationBuilderKit.LinkWrapper(LINK_TYPE_PARENT + parentName), $(parentName));
        else cat.addItem(parentName);
      }
      cat.addItem(")");
    }
    return cat;
  }

  //
  private static Iterable<String> describeDeco(
    PyDecorator deco,
    final FP.Lambda1<Iterable<String>, Iterable<String>> name_wrapper, //  addWith in tags, if need be
    final FP.Lambda1<String, String> arg_wrapper   // add escaping, if need be
  ) {
    ChainIterable<String> cat = new ChainIterable<String>();
    cat.addItem("@").addWith(name_wrapper, $(PyUtil.getReadableRepr(deco.getCallee(), true)));
    if (deco.hasArgumentList()) {
      PyArgumentList arglist = deco.getArgumentList();
      if (arglist != null) {
        cat
          .addItem("(")
          .add(interleave(FP.map(FP.combine(LReadableRepr, arg_wrapper), arglist.getArguments()), ", "))
          .addItem(")")
        ;
      }
    }
    return cat;
  }

  private static boolean specifiesReturnType(PyStringLiteralExpression docStringExpression) {
    String value = PyUtil.strValue(docStringExpression);
    if (value == null) {
      return false;
    }
    PyDocumentationSettings documentationSettings = PyDocumentationSettings.getInstance(docStringExpression.getProject());
    if (documentationSettings.isEpydocFormat(docStringExpression.getContainingFile())) {
      return value.contains("@rtype");
    }
    else if (documentationSettings.isReSTFormat(docStringExpression.getContainingFile())) {
      return value.contains(":rtype:");
    }
    return false;
  }

  // provides ctrl+Q doc
  public String generateDoc(PsiElement element, final PsiElement originalElement) {
    if (element != null && PydevConsoleRunner.isInPydevConsole(element) ||
      originalElement != null && PydevConsoleRunner.isInPydevConsole(originalElement)){
      return PydevDocumentationProvider.createDoc(element, originalElement);
    }
    return new DocumentationBuilder(element, originalElement).build();
  }

  @Override
  public PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
    if (link.equals(LINK_TYPE_CLASS)) {
      return inferContainingClassOf(context);
    }
    else if (link.equals(LINK_TYPE_PARAM)) {
      return inferClassOfParameter(context);
    }
    else if (link.startsWith(LINK_TYPE_PARENT)) {
      PyClass cls = inferContainingClassOf(context);
      if (cls != null) {
        String desired_name = link.substring(LINK_TYPE_PARENT.length());
        for (PyClassRef parent : cls.iterateAncestors()) {
          final String parent_name = parent.getClassName();
          if (parent_name != null && parent_name.equals(desired_name)) return parent.getPyClass();
        }
      }
    }
    return null;
  }

  @Override
  public List<String> getUrlFor(final PsiElement element, PsiElement originalElement) {
    final String url = getUrlFor(element, originalElement, true);
    return url == null ? null : Collections.singletonList(url);
  }

  @Nullable
  private static String getUrlFor(PsiElement element, PsiElement originalElement, boolean checkExistence) {
    PsiFileSystemItem file = element instanceof PsiFileSystemItem ? (PsiFileSystemItem) element : element.getContainingFile();
    if (file == null) return null;
    if (PyNames.INIT_DOT_PY.equals(file.getName())) {
      file = file.getParent();
      assert file != null;
    }
    Sdk sdk = PyBuiltinCache.findSdkForFile(file);
    if (sdk == null) {
      return null;
    }
    PyQualifiedName qName = ResolveImportUtil.findCanonicalImportPath(element, originalElement);
    if (qName == null) {
      return null;
    }
    PythonDocumentationMap map = PythonDocumentationMap.getInstance();
    String pyVersion = pyVersion(sdk.getVersionString());
    PsiNamedElement namedElement = (element instanceof PsiNamedElement && !(element instanceof PsiFileSystemItem))
                                   ? (PsiNamedElement) element
                                   : null;
    if (namedElement instanceof PyFunction && PyNames.INIT.equals(namedElement.getName())) {
      final PyClass containingClass = ((PyFunction)namedElement).getContainingClass();
      if (containingClass != null) {
        namedElement = containingClass;
      }
    }
    String url = map.urlFor(qName, namedElement, pyVersion);
    if (url != null) {
      if (checkExistence && !pageExists(url)) {
        return map.rootUrlFor(qName);
      }
      return url;
    }
    for (PythonDocumentationLinkProvider provider : Extensions.getExtensions(PythonDocumentationLinkProvider.EP_NAME)) {
      final String providerUrl = provider.getExternalDocumentationUrl(element, originalElement);
      if (providerUrl != null) {
        if (checkExistence && !pageExists(providerUrl)) {
          return provider.getExternalDocumentationRoot(sdk);
        }
        return providerUrl;
      }
    }
    return null;
  }

  private static boolean pageExists(String url) {
    HttpClient client = new HttpClient();
    client.setTimeout(5 * 1000);
    client.setConnectionTimeout(5 * 1000);
    HeadMethod method = new HeadMethod(url);
    try {
      int rc = client.executeMethod(method);
      if (rc == 404) {
        return false;
      }
    }
    catch (IOException ignored) {
    }
    return true;
  }

  @Nullable
  public static String pyVersion(@Nullable String versionString) {
    String prefix = "Python ";
    if (versionString != null && versionString.startsWith(prefix)) {
      String version = versionString.substring(prefix.length());
      int dot = version.indexOf('.');
      if (dot > 0) {
        dot = version.indexOf('.', dot+1);
        if (dot > 0) {
          return version.substring(0, dot);
        }
        return version;
      }
    }
    return null;
  }

  @Override
  public String fetchExternalDocumentation(Project project, PsiElement element, List<String> docUrls) {
    return null;
  }

  @Override
  public boolean hasDocumentationFor(PsiElement element, PsiElement originalElement) {
    return getUrlFor(element, originalElement, false) != null;
  }

  @Override
  public boolean canPromptToConfigureDocumentation(PsiElement element) {
    final PsiFile containingFile = element.getContainingFile();
    if (containingFile instanceof PyFile) {
      final Project project = element.getProject();
      final VirtualFile vFile = containingFile.getVirtualFile();
      if (vFile != null && ProjectRootManager.getInstance(project).getFileIndex().isInLibraryClasses(vFile)) {
        final PyQualifiedName qName = ResolveImportUtil.findCanonicalImportPath(element, element);
        if (qName != null && qName.getComponentCount() > 0) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void promptToConfigureDocumentation(PsiElement element) {
    final Project project = element.getProject();
    final PyQualifiedName qName = ResolveImportUtil.findCanonicalImportPath(element, element);
    if (qName != null && qName.getComponentCount() > 0) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            int rc = Messages.showOkCancelDialog(project,
                                                 "No external documentation URL configured for module " + qName.getComponents().get(0) +
                                                 ".\nWould you like to configure it now?",
                                                 "Python External Documentation",
                                                 Messages.getQuestionIcon());
            if (rc == 0) {
              ShowSettingsUtil.getInstance().showSettingsDialog(project, PythonDocumentationConfigurable.class);
            }
          }
        }, ModalityState.NON_MODAL);
    }
  }

  @Nullable
  private static PyClass inferContainingClassOf(PsiElement context) {
    if (context instanceof PyClass) return (PyClass)context;
    if (context instanceof PyFunction) return ((PyFunction)context).getContainingClass();
    else return PsiTreeUtil.getParentOfType(context, PyClass.class);
  }

  @Nullable
  private static PyClass inferClassOfParameter(PsiElement context) {
    final PyType type = ((PyNamedParameter)context).getType(TypeEvalContext.fast());
    if (type instanceof PyClassType)
      return ((PyClassType)type).getPyClass();
    return null;
  }

  public static final DocumentationBuilderKit.LinkWrapper LinkMyClass = new DocumentationBuilderKit.LinkWrapper(LINK_TYPE_CLASS); // link item to containing class

  public static String generateDocumentationContentStub(PyFunction element, String offset, boolean checkReturn) {
    Project project = element.getProject();
    PyDocumentationSettings documentationSettings = PyDocumentationSettings.getInstance(project);
    String result = "";
    if (documentationSettings.isEpydocFormat(element.getContainingFile()))
      result += generateContent(element, offset, EPYDOC_PREFIX, checkReturn);
    else if (documentationSettings.isReSTFormat(element.getContainingFile()))
      result += generateContent(element, offset, RST_PREFIX, checkReturn);
    else
      result += offset;
    return result;
  }

  public static void inserDocStub(PyFunction function, PyStatementList insertPlace, Project project, Editor editor) {
    PyElementGenerator elementGenerator = PyElementGenerator.getInstance(project);
    PsiWhiteSpace whitespace = PsiTreeUtil.getPrevSiblingOfType(insertPlace, PsiWhiteSpace.class);
    String ws = "\n";
    if (whitespace != null) {
      String[] spaces = whitespace.getText().split("\n");
      if (spaces.length > 1)
        ws = ws + spaces[spaces.length-1];
    }
    String docContent = ws + generateDocumentationContentStub(function, ws, true);
    PyExpressionStatement string = elementGenerator.createDocstring("\"\"\"" + docContent + "\"\"\"");
    if (insertPlace.getStatements().length != 0)
      insertPlace.addBefore(string, insertPlace.getStatements()[0]);
    PyStringLiteralExpression docstring = function.getDocStringExpression();
    if (editor != null && docstring != null) {
      int offset = docstring.getTextOffset();
      editor.getCaretModel().moveToOffset(offset);
      editor.getCaretModel().moveCaretRelatively(0, 1, false, false, false);
    }
  }

  public static void inserDocStub(PyFunction function, Project project, Editor editor) {
    inserDocStub(function, function.getStatementList(), project, editor);
  }

  public String generateDocumentationContentStub(PyFunction element, boolean checkReturn) {
    PsiWhiteSpace whitespace = PsiTreeUtil.getPrevSiblingOfType(element.getStatementList(), PsiWhiteSpace.class);
    String ws = "\n";
    if (whitespace != null) {
      String[] spaces = whitespace.getText().split("\n");
      if (spaces.length > 1)
        ws = ws + whitespace.getText().split("\n")[1];
    }
    return generateDocumentationContentStub(element, ws, checkReturn);
  }

  private static String generateContent(PyFunction element, String offset, String prefix, boolean checkReturn) {
    PyParameter[] list = element.getParameterList().getParameters();
    StringBuilder builder = new StringBuilder(offset);
    for(PyParameter p : list) {
      if (p.getText().equals(PyNames.CANONICAL_SELF))
        continue;
      builder.append(prefix);
      builder.append("param ");
      builder.append(p.getName());
      builder.append(": ");
      builder.append(offset);
      if (PyCodeInsightSettings.getInstance().INSERT_TYPE_DOCSTUB) {
        builder.append(prefix);
        builder.append("type ");
        builder.append(p.getName());
        builder.append(": ");
        builder.append(offset);
      }
    }
    if (checkReturn) {
      RaiseVisitor visitor = new RaiseVisitor();
      PyStatementList statementList = element.getStatementList();
      if (statementList != null) {
        statementList.accept(visitor);
      }
      if (visitor.myHasReturn) {
        builder.append(prefix).append("return:").append(offset);
        if (PyCodeInsightSettings.getInstance().INSERT_TYPE_DOCSTUB) {
          builder.append(prefix).append("rtype:").append(offset);
        }
      }
      if (visitor.myHasRaise)
        builder.append(prefix).append("raise:").append(offset);
    }
    else {
      builder.append(prefix).append("return:").append(offset);
      if (PyCodeInsightSettings.getInstance().INSERT_TYPE_DOCSTUB) {
        builder.append(prefix).append("rtype:").append(offset);
      }
    }
    return builder.toString();
  }

  private static class RaiseVisitor extends PyRecursiveElementVisitor {
    private boolean myHasRaise = false;
    private boolean myHasReturn = false;
    @Override
    public void visitPyRaiseStatement(PyRaiseStatement node) {
      myHasRaise = true;
    }
    @Override
    public void visitPyReturnStatement(PyReturnStatement node) {
      myHasReturn = true;
    }
  }
}
