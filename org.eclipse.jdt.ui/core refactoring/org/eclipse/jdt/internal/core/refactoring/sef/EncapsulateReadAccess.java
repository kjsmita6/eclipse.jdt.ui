/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.core.refactoring.sef;

import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.core.codemanipulation.SimpleTextEdit;
import org.eclipse.jdt.internal.core.codemanipulation.TextEdit;
import org.eclipse.jdt.internal.core.codemanipulation.TextPosition;

final class EncapsulateReadAccess extends SimpleTextEdit {

	public EncapsulateReadAccess(String getter, SingleNameReference node) {
		this(getter, node.sourceStart, node.sourceEnd - node.sourceStart + 1);
	}
	
	public EncapsulateReadAccess(String getter, int offset, int length) {
		super(offset, length, getter + "()");
	}
	
	private EncapsulateReadAccess(TextPosition position, String text) {
		super(position, text);
	}
	
	/* non Java-doc
	 * @see TextEdit#getCopy
	 */
	public TextEdit copy() {
		return new EncapsulateReadAccess(getTextPosition().copy(), getText());
	}	
}

