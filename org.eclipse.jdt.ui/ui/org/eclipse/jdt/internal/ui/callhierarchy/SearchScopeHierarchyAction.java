/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *   Jesper Kamstrup Linnet (eclipse@kamstrup-linnet.dk) - initial API and implementation 
 *          (report 36180: Callers/Callees view)
 ******************************************************************************/
package org.eclipse.jdt.internal.ui.callhierarchy;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;

import org.eclipse.ui.help.WorkbenchHelp;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;
import org.eclipse.jdt.internal.ui.JavaPlugin;


class SearchScopeHierarchyAction extends SearchScopeAction {
	private final SearchScopeActionGroup fGroup;
	
	public SearchScopeHierarchyAction(SearchScopeActionGroup group) {
		super(group, CallHierarchyMessages.getString("SearchScopeActionGroup.hierarchy.text")); //$NON-NLS-1$
		this.fGroup = group;
		setToolTipText(CallHierarchyMessages.getString("SearchScopeActionGroup.hierarchy.tooltip")); //$NON-NLS-1$
		WorkbenchHelp.setHelp(this, IJavaHelpContextIds.CALL_HIERARCHY_SEARCH_SCOPE_ACTION);
	}
	
	public IJavaSearchScope getSearchScope() {
		try {
			IMethod method = this.fGroup.getView().getMethod();
			
			if (method != null) {
				return SearchEngine.createHierarchyScope(method.getDeclaringType());
			} else {
				return null;
			}
		} catch (JavaModelException e) {
			JavaPlugin.log(e);
		}
		
		return null;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.ui.callhierarchy.SearchScopeActionGroup.SearchScopeAction#getSearchScopeType()
	 */
	public int getSearchScopeType() {
		return SearchScopeActionGroup.SEARCH_SCOPE_TYPE_HIERARCHY;
	}
}
