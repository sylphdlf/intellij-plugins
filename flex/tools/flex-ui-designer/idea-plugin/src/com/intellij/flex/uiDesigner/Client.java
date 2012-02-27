package com.intellij.flex.uiDesigner;

import com.intellij.flex.uiDesigner.abc.ClassPoolGenerator;
import com.intellij.flex.uiDesigner.io.*;
import com.intellij.flex.uiDesigner.libraries.*;
import com.intellij.flex.uiDesigner.mxml.MxmlWriter;
import com.intellij.flex.uiDesigner.mxml.ProjectComponentReferenceCounter;
import com.intellij.javascript.flex.mxml.FlexCommonTypeNames;
import com.intellij.lang.javascript.flex.XmlBackedJSClassImpl;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.psi.resolve.JSInheritanceUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import gnu.trove.TObjectObjectProcedure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class Client implements Disposable {
  protected final BlockDataOutputStream blockOut = new BlockDataOutputStream();
  protected final AmfOutputStream out = new AmfOutputStream(blockOut);

  private final InfoMap<Module, ModuleInfo> registeredModules = new InfoMap<Module, ModuleInfo>(true);
  private final InfoMap<Project, ProjectInfo> registeredProjects = new InfoMap<Project, ProjectInfo>();

  public static Client getInstance() {
    return DesignerApplicationManager.getService(Client.class);
  }

  public void setOut(@NotNull OutputStream socketOut) {
    blockOut.setOut(socketOut);
  }

  public boolean isModuleRegistered(Module module) {
    return registeredModules.contains(module);
  }

  public InfoMap<Project, ProjectInfo> getRegisteredProjects() {
    return registeredProjects;
  }

  public InfoMap<Module, ModuleInfo> getRegisteredModules() {
    return registeredModules;
  }

  @NotNull
  public Module getModule(int id) {
    return registeredModules.getElement(id);
  }

  @NotNull
  public Project getProject(int id) {
    return registeredProjects.getElement(id);
  }

  @Override
  public void dispose() {
    registeredModules.dispose();
  }

  public boolean flush() {
    try {
      out.flush();
      return true;
    }
    catch (IOException e) {
      LogMessageUtil.processInternalError(e);
    }

    return false;
  }

  private void beginMessage(ClientMethod method) {
    beginMessage(method, null);
  }

  private void beginMessage(ClientMethod method, @Nullable ActionCallback callback) {
    blockOut.assertStart();
    out.write(ClientMethod.METHOD_CLASS);
    out.write(callback == null ? 0 : SocketInputHandler.getInstance().addCallback(callback));
    out.write(method);
  }

  public void openProject(Project project) {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.openProject);
      writeId(project);
      out.writeAmfUtf(project.getName());
      ProjectWindowBounds.write(project, out);
      hasError = false;
    }
    finally {
      finalizeMessageAndFlush(hasError);
    }
  }

  public void closeProject(final Project project) {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.closeProject);
      writeId(project);
      hasError = false;
    }
    finally {
      try {
        finalizeMessageAndFlush(hasError);
      }
      finally {
        unregisterProject(project);
      }
    }
  }

  public void unregisterProject(final Project project) {
    DocumentFactoryManager.getInstance().unregister(project);
    
    registeredProjects.remove(project);
    if (registeredProjects.isEmpty()) {
      registeredModules.clear();
    }
    else {
      registeredModules.remove(new TObjectObjectProcedure<Module, ModuleInfo>() {
        @Override
        public boolean execute(Module module, ModuleInfo info) {
          return module.getProject() != project;
        }
      });
    }
  }

  public void unregisterModule(final Module module) {
    registeredModules.remove(module);
    // todo close related documents
  }

  public void updateStringRegistry(StringRegistry.StringWriter stringWriter) throws IOException {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.updateStringRegistry);
      stringWriter.writeTo(out);
      hasError = false;
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  public void registerLibrarySet(LibrarySet librarySet) {
    final List<Library> styleOwners = new ArrayList<Library>();
    for (Library library : librarySet.getLibraries()) {
      if (library.isStyleOwner()) {
        styleOwners.add(library);
      }
    }

    boolean hasError = true;
    try {
      beginMessage(ClientMethod.registerLibrarySet);
      out.writeUInt29(librarySet.getId());
      out.write(librarySet instanceof FlexLibrarySet);

      LibrarySet parent = librarySet.getParent();
      out.writeShort(parent == null ? -1 : parent.getId());

      out.write(styleOwners.size());
      final LibraryManager libraryManager = LibraryManager.getInstance();
      for (Library library : styleOwners) {
        final boolean registered = libraryManager.isRegistered(library);
        out.write(registered);

        if (registered) {
          out.writeUInt29(library.getId());
        }
        else {
          out.writeUInt29(libraryManager.add(library));
          writeVirtualFile(library.getFile(), out);

          if (library.inheritingStyles == null) {
            out.writeShort(0);
          }
          else {
            out.write(library.inheritingStyles);
          }

          if (library.defaultsStyle == null) {
            out.write(0);
          }
          else {
            out.write(1);
            out.write(library.defaultsStyle);
          }
        }
      }

      hasError = false;
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  public void registerModule(Project project, ModuleInfo moduleInfo, StringRegistry.StringWriter stringWriter) {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.registerModule);
      stringWriter.writeToIfStarted(out);

      out.writeShort(registeredModules.add(moduleInfo));
      writeId(project);
      out.write(moduleInfo.isApp());

      out.write(Amf3Types.VECTOR_INT);
      out.writeUInt29((moduleInfo.getLibrarySets().size() << 1) | 1);
      out.write(true);
      for (LibrarySet librarySet : moduleInfo.getLibrarySets()) {
        out.writeInt(librarySet.getId());
      }

      out.write(moduleInfo.getLocalStyleHolders(), "lsh", true);
      hasError = false;
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  public void renderDocument(Module module, XmlFile psiFile) {
    renderDocument(module, psiFile, new ProblemsHolder());
  }

  /**
   * final, full render document — responsible for handle problemsHolder and assetCounter — you must not do it
   */
  public boolean renderDocument(Module module, XmlFile psiFile, ProblemsHolder problemsHolder) {
    final DocumentFactoryManager documentFactoryManager = DocumentFactoryManager.getInstance();
    final VirtualFile virtualFile = psiFile.getVirtualFile();

    assert virtualFile != null;

    if (documentFactoryManager.isRegistered(virtualFile)) {
      FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
      Document document = fileDocumentManager.getDocument(virtualFile);
      if (document != null && fileDocumentManager.isDocumentUnsaved(document)) {
        return updateDocumentFactory(documentFactoryManager.getId(virtualFile), module, psiFile);
      }
    }

    final int factoryId = registerDocumentFactoryIfNeed(module, psiFile, virtualFile, false, problemsHolder);
    if (factoryId == -1) {
      return false;
    }

    FlexLibrarySet flexLibrarySet = registeredModules.getInfo(module).getFlexLibrarySet();
    if (flexLibrarySet != null) {
      fillAssetClassPoolIfNeed(flexLibrarySet);
    }

    if (!problemsHolder.isEmpty()) {
      DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
    }

    beginMessage(ClientMethod.renderDocument);
    out.writeShort(factoryId);

    return true;
  }

  public void fillAssetClassPoolIfNeed(FlexLibrarySet librarySet) {
    final AssetCounterInfo assetCounterInfo = librarySet.assetCounterInfo;
    int diff = assetCounterInfo.demanded.imageCount - assetCounterInfo.allocated.imageCount;
    if (diff > 0) {
      // reduce number of call fill asset class pool
      diff *= 2;
      fillAssetClassPool(librarySet, diff, ClassPoolGenerator.Kind.IMAGE);
      assetCounterInfo.allocated.imageCount += diff;
    }

    diff = assetCounterInfo.demanded.swfCount - assetCounterInfo.allocated.swfCount;
    if (diff > 0) {
      // reduce number of call fill asset class pool
      diff *= 2;
      fillAssetClassPool(librarySet, diff, ClassPoolGenerator.Kind.SWF);
      assetCounterInfo.allocated.swfCount += diff;
    }

    diff = assetCounterInfo.demanded.viewCount - assetCounterInfo.allocated.viewCount;
    if (diff > 0) {
      // reduce number of call fill asset class pool
      diff *= 2;
      fillAssetClassPool(librarySet, diff, ClassPoolGenerator.Kind.SPARK_VIEW);
      assetCounterInfo.allocated.viewCount += diff;
    }
  }

  private void fillAssetClassPool(FlexLibrarySet flexLibrarySet, int classCount, ClassPoolGenerator.Kind kind) {
    boolean hasError = true;
    try {
      if (kind == ClassPoolGenerator.Kind.IMAGE) {
        beginMessage(ClientMethod.fillImageClassPool);
      }
      else {
        beginMessage(kind == ClassPoolGenerator.Kind.SWF ? ClientMethod.fillSwfClassPool : ClientMethod.fillViewClassPool);
      }

      writeId(flexLibrarySet.getId());
      out.writeShort(classCount);
      ClassPoolGenerator.generate(kind, classCount, flexLibrarySet.assetCounterInfo.allocated, blockOut);
      hasError = false;
    }
    catch (Throwable e) {
      LogMessageUtil.processInternalError(e, null);
    }
    finally {
      finalizeMessage(hasError);
    }
  }

  private void finalizeMessage(boolean hasError) {
    if (hasError) {
      blockOut.rollback();
    }
    else {
      try {
        blockOut.end();
      }
      catch (IOException e) {
        LogMessageUtil.processInternalError(e);
      }
    }

    out.resetAfterMessage();
  }

  private void finalizeMessageAndFlush(boolean hasError) {
    if (hasError) {
      blockOut.rollback();
    }
    else {
      try {
        out.flush();
      }
      catch (IOException e) {
        LogMessageUtil.processInternalError(e);
      }
    }
  }

  public boolean updateDocumentFactory(int factoryId, Module module, XmlFile psiFile) {
    try {
      beginMessage(ClientMethod.updateDocumentFactory);
      out.writeShort(factoryId);

      final ProblemsHolder problemsHolder = new ProblemsHolder();
      writeDocumentFactory(DocumentFactoryManager.getInstance().getInfo(factoryId), module, psiFile, problemsHolder);
      if (!problemsHolder.isEmpty()) {
        DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
      }

      beginMessage(ClientMethod.renderDocumentAndDependents);
      writeId(module);
      out.writeShort(factoryId);
      blockOut.end();
      return true;
    }
    catch (Throwable e) {
      LogMessageUtil.processInternalError(e, psiFile.getVirtualFile());
    }

    blockOut.rollback();
    return false;
  }

  private int registerDocumentFactoryIfNeed(Module module, XmlFile psiFile, VirtualFile virtualFile, boolean force,
                                            ProblemsHolder problemsHolder) {
    final DocumentFactoryManager documentFactoryManager = DocumentFactoryManager.getInstance();
    final boolean registered = !force && documentFactoryManager.isRegistered(virtualFile);
    final DocumentFactoryManager.DocumentInfo documentInfo = documentFactoryManager.get(virtualFile, null, null);
    if (!registered) {
      boolean hasError = true;
      try {
        beginMessage(ClientMethod.registerDocumentFactory);
        writeId(module);
        out.writeShort(documentInfo.getId());
        writeVirtualFile(virtualFile, out);
        hasError = !writeDocumentFactory(documentInfo, module, psiFile, problemsHolder);
      }
      catch (Throwable e) {
        LogMessageUtil.processInternalError(e, virtualFile);
      }
      finally {
        if (hasError) {
          blockOut.rollback();
          //noinspection ReturnInsideFinallyBlock
          return -1;
        }
      }
    }

    return documentInfo.getId();
  }

  private boolean writeDocumentFactory(DocumentFactoryManager.DocumentInfo documentInfo,
                                       Module module,
                                       XmlFile psiFile,
                                       ProblemsHolder problemsHolder) throws IOException {
    final AccessToken token = ReadAction.start();
    final int flags;
    try {
      final JSClass jsClass = XmlBackedJSClassImpl.getXmlBackedClass(psiFile);
      assert jsClass != null;
      out.writeAmfUtf(jsClass.getQualifiedName());

      if (JSInheritanceUtil.isParentClass(jsClass, FlexCommonTypeNames.SPARK_APPLICATION) ||
          JSInheritanceUtil.isParentClass(jsClass, FlexCommonTypeNames.MX_APPLICATION)) {
        flags = 1;
      }
      else if (JSInheritanceUtil.isParentClass(jsClass, FlexCommonTypeNames.IUI_COMPONENT)) {
        flags = 0;
      }
      else {
        flags = 2;
      }
    }
    finally {
      token.finish();
    }

    out.write(flags);

    Pair<ProjectComponentReferenceCounter, List<RangeMarker>> result =
      new MxmlWriter(out, problemsHolder, registeredModules.getInfo(module).getFlexLibrarySet().assetCounterInfo.demanded).write(psiFile);
    if (result == null) {
      return false;
    }

    documentInfo.setRangeMarkers(result.second);
    return result.first.unregistered.isEmpty() || registerDocumentReferences(result.first.unregistered, module, problemsHolder);
  }

  public boolean registerDocumentReferences(List<XmlFile> files, Module module, ProblemsHolder problemsHolder) {
    for (XmlFile file : files) {
      VirtualFile virtualFile = file.getVirtualFile();
      assert virtualFile != null;
      Module documentModule = ModuleUtil.findModuleForFile(virtualFile, file.getProject());
      if (module != documentModule && !isModuleRegistered(module)) {
        try {
          LibraryManager.getInstance().initLibrarySets(module, problemsHolder);
        }
        catch (InitException e) {
          LogMessageUtil.LOG.error(e.getCause());
          // todo unclear error message (module will not be specified in this error message (but must be))
          problemsHolder.add(e.getMessage());
        }
      }

      // force register, it is registered (id allocated) only on server side
      if (registerDocumentFactoryIfNeed(module, file, virtualFile, true, problemsHolder) == -1) {
        return false;
      }
    }

    return true;
  }

  public void selectComponent(int documentId, int componentId) {
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.selectComponent);
      out.writeShort(documentId);
      out.writeShort(componentId);
      hasError = false;
    }
    finally {
      finalizeMessageAndFlush(hasError);
    }
  }

  //public AsyncResult<BufferedImage> getDocumentImage(DocumentFactoryManager.DocumentInfo documentInfo) {
  //  final AsyncResult<BufferedImage> result = new AsyncResult<BufferedImage>();
  //  getDocumentImage(documentInfo, result);
  //  return result;
  //}

  public void getDocumentImage(DocumentFactoryManager.DocumentInfo documentInfo, final AsyncResult<BufferedImage> result) {
    final ActionCallback callback = new ActionCallback();
    boolean hasError = true;
    try {
      beginMessage(ClientMethod.getDocumentImage, callback);
      callback.notifyWhenRejected(result);
      callback.doWhenDone(new Runnable() {
        @Override
        public void run() {
          SocketInputHandlerImpl.Reader reader = SocketInputHandler.getInstance().getReader();
          try {
            result.setDone(reader.readImage());
          }
          catch (IOException e) {
            LogMessageUtil.LOG.error(e);
            result.setRejected();
          }
        }
      });

      out.writeShort(documentInfo.getId());
      hasError = false;
    }
    finally {
      finalizeMessageAndFlush(hasError);
      if (hasError) {
        callback.setRejected();
      }
    }
  }

  public static void writeVirtualFile(VirtualFile file, PrimitiveAmfOutputStream out) {
    out.writeAmfUtf(file.getUrl());
    out.writeAmfUtf(file.getPresentableUrl());
  }

  public void initStringRegistry() throws IOException {
    StringRegistry stringRegistry = StringRegistry.getInstance();
    beginMessage(ClientMethod.initStringRegistry);
    out.write(stringRegistry.toArray());

    blockOut.end();
  }

  public void writeId(Module module, PrimitiveAmfOutputStream out) {
    out.writeShort(registeredModules.getId(module));
  }

  private void writeId(Module module) {
    writeId(module, out);
  }

  private void writeId(Project project) {
    writeId(registeredProjects.getId(project));
  }

  private void writeId(int id) {
    out.writeShort(id);
  }

  public static enum ClientMethod {
    openProject, closeProject, registerLibrarySet, registerModule, registerDocumentFactory, updateDocumentFactory, renderDocument, renderDocumentAndDependents,
    initStringRegistry, updateStringRegistry, fillImageClassPool, fillSwfClassPool, fillViewClassPool,
    selectComponent, getDocumentImage;
    
    public static final int METHOD_CLASS = 0;
  }
}