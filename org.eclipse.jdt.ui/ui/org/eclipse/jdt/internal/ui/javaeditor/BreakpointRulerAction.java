package org.eclipse.jdt.internal.ui.javaeditor;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.IDebugConstants;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.internal.debug.ui.BreakpointLocationVerifier;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.AbstractMarkerAnnotationModel;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerRulerAction;


/**
 *
 */
public class BreakpointRulerAction extends MarkerRulerAction {	
		
	
	public BreakpointRulerAction(IVerticalRuler ruler, ITextEditor editor) {
		super(JavaEditorMessages.getResourceBundle(), "ManageBreakpoints.", ruler, editor, IDebugConstants.BREAKPOINT_MARKER, false); //$NON-NLS-1$
	}
	
	
	/**
	 * Checks whether the element the breakpoint refers to is shown in this editor
	 */
	protected boolean breakpointElementInEditor(IBreakpointManager manager, IMarker marker) {
		return true;
	}
	
	/**
	 * @see MarkerRulerAction#getMarkers
	 */
	protected List getMarkers() {
		
		List breakpoints= new ArrayList();
		
		IResource resource= getResource();
		IDocument document= getDocument();
		AbstractMarkerAnnotationModel model= getAnnotationModel();
		
		if (model != null) {
			try {
				
				IMarker[] markers= null;
				if (resource instanceof IFile)
					markers= resource.findMarkers(IDebugConstants.BREAKPOINT_MARKER, true, IResource.DEPTH_INFINITE);
				else {
					IWorkspaceRoot root= JavaPlugin.getWorkspace().getRoot();
					//fix for: 1GEUMGZ
					markers= root.findMarkers(IDebugConstants.BREAKPOINT_MARKER, true, IResource.DEPTH_INFINITE);
				}
				
				if (markers != null) {
					IBreakpointManager breakpointManager= DebugPlugin.getDefault().getBreakpointManager();
					for (int i= 0; i < markers.length; i++) {
						IBreakpoint breakpoint= breakpointManager.getBreakpoint(markers[i]);
						if (breakpoint != null && breakpointManager.isRegistered(breakpoint) && 
								breakpointElementInEditor(breakpointManager, markers[i]) && 
								includesRulerLine(model.getMarkerPosition(markers[i]), document))
							breakpoints.add(markers[i]);
					}
				}
			} catch (CoreException x) {
				JavaPlugin.logErrorStatus(JavaEditorMessages.getString("ManageBreakpoints.error.retrieving.message"), x.getStatus()); //$NON-NLS-1$
			}
		}
		return breakpoints;
	}
	
	/**
	 * @see MarkerRulerAction#addMarker
	 */
	protected void addMarker() {
		
		IEditorInput editorInput= getTextEditor().getEditorInput();
		
		IDocument document= getDocument();
		int rulerLine= getVerticalRuler().getLineOfLastMouseButtonActivity();
		IBreakpointManager breakpointManager= DebugPlugin.getDefault().getBreakpointManager();
		
		try {
			BreakpointLocationVerifier bv = new BreakpointLocationVerifier();
			int lineNumber = bv.getValidBreakpointLocation(document, rulerLine);
			if (lineNumber > 0) {
				
				IRegion line= document.getLineInformation(lineNumber - 1);
				
				IType type = null;
				if (editorInput instanceof ClassFileEditorInput) {
					ClassFileEditorInput input= (ClassFileEditorInput) editorInput;
					type = input.getClassFile().getType();
				} else if (editorInput instanceof IFileEditorInput) {
					IFileEditorInput input= (IFileEditorInput) editorInput;
					ICompilationUnit cu = (ICompilationUnit) JavaCore.create(input.getFile());
					IJavaElement e = cu.getElementAt(line.getOffset());
					if (e instanceof IType) 
						type = (IType)e;
					else if (e != null && e instanceof IMember) {
						type = ((IMember)e).getDeclaringType();
					}
				}
				if (type != null) {
					if (!JDIDebugModel.lineBreakpointExists(type, lineNumber)) {
							IBreakpoint breakpoint = JDIDebugModel.createLineBreakpoint(type, lineNumber, line.getOffset(), line.getOffset() + line.getLength(), 0);
					}
				}
			}
		} catch (DebugException e) {
			Shell shell= getTextEditor().getSite().getShell();
			ErrorDialog.openError(shell, JavaEditorMessages.getString("ManageBreakpoints.error.adding.title1"), JavaEditorMessages.getString("ManageBreakpoints.error.adding.message1"), e.getStatus()); //$NON-NLS-2$ //$NON-NLS-1$
		} catch (CoreException e) {
			Shell shell= getTextEditor().getSite().getShell();
			ErrorDialog.openError(shell, JavaEditorMessages.getString("ManageBreakpoints.error.adding.title2"), JavaEditorMessages.getString("ManageBreakpoints.error.adding.message2"), e.getStatus()); //$NON-NLS-2$ //$NON-NLS-1$
		} catch (BadLocationException e) {
			Shell shell= getTextEditor().getSite().getShell();
			ErrorDialog.openError(shell, JavaEditorMessages.getString("ManageBreakpoints.error.adding.title3"), JavaEditorMessages.getString("ManageBreakpoints.error.adding.message3"), null); //$NON-NLS-2$ //$NON-NLS-1$
		}
	}
	
	/**
	 * @see MarkerRulerAction#removeMarkers
	 */
	protected void removeMarkers(List markers) {
		IBreakpointManager breakpointManager= DebugPlugin.getDefault().getBreakpointManager();
		try {
			
			Iterator e= markers.iterator();
			while (e.hasNext()) {
				IBreakpoint breakpoint= breakpointManager.getBreakpoint((IMarker) e.next());
				breakpointManager.removeBreakpoint(breakpoint, true);
			}
			
		} catch (CoreException e) {
			Shell shell= getTextEditor().getSite().getShell();
			ErrorDialog.openError(shell, JavaEditorMessages.getString("ManageBreakpoints.error.removing.title1"), JavaEditorMessages.getString("ManageBreakpoints.error.removing.message1"), e.getStatus()); //$NON-NLS-2$ //$NON-NLS-1$
		}
	}
}