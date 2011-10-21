/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.propertiesfileeditor;

import org.eclipse.core.resources.IFile;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.TextInvocationContext;

/**
 * The properties file quick assist context.
 * 
 * @since 3.8
 * 
 */
public class PropertiesAssistContext extends TextInvocationContext {

	private final IFile fFile;

	private final IDocument fDocument;

	public PropertiesAssistContext(ISourceViewer sourceViewer, int offset, int length, IFile file, IDocument document) {
		super(sourceViewer, offset, length);
		fFile= file;
		fDocument= document;
	}

	public IFile getFile() {
		return fFile;
	}

	public IDocument getDocument() {
		return fDocument;
	}
}