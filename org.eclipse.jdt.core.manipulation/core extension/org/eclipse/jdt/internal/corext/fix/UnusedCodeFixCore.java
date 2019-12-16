/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Red Hat Inc. - copied and modified for use in jdt.core.manipulation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.fix;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.text.edits.TextEditGroup;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.manipulation.CleanUpOptionsCore;
import org.eclipse.jdt.core.manipulation.ICleanUpFixCore;

import org.eclipse.jdt.internal.core.manipulation.dom.NecessaryParenthesesChecker;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.LinkedNodeFinder;
import org.eclipse.jdt.internal.corext.dom.StatementRewrite;
import org.eclipse.jdt.internal.corext.refactoring.structure.CompilationUnitRewrite;
import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.internal.ui.fix.UnusedCodeCleanUpCore;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.internal.ui.text.correction.JavadocTagsSubProcessorCore;
import org.eclipse.jdt.internal.ui.text.correction.ProblemLocationCore;

/**
 * Fix which removes unused code.
 */
public class UnusedCodeFixCore extends CompilationUnitRewriteOperationsFixCore {

	public static class SideEffectFinder extends ASTVisitor {

		private final ArrayList<Expression> fSideEffectNodes;

		public SideEffectFinder(ArrayList<Expression> res) {
			fSideEffectNodes= res;
		}

		@Override
		public boolean visit(Assignment node) {
			fSideEffectNodes.add(node);
			return false;
		}

		@Override
		public boolean visit(PostfixExpression node) {
			fSideEffectNodes.add(node);
			return false;
		}

		@Override
		public boolean visit(PrefixExpression node) {
			Object operator= node.getOperator();
			if (operator == PrefixExpression.Operator.INCREMENT || operator == PrefixExpression.Operator.DECREMENT) {
				fSideEffectNodes.add(node);
				return false;
			}
			return true;
		}

		@Override
		public boolean visit(MethodInvocation node) {
			fSideEffectNodes.add(node);
			return false;
		}

		@Override
		public boolean visit(ClassInstanceCreation node) {
			fSideEffectNodes.add(node);
			return false;
		}

		@Override
		public boolean visit(SuperMethodInvocation node) {
			fSideEffectNodes.add(node);
			return false;
		}
	}

	public static class RemoveImportOperation extends CompilationUnitRewriteOperation {

		private final ImportDeclaration fImportDeclaration;

		public RemoveImportOperation(ImportDeclaration importDeclaration) {
			fImportDeclaration= importDeclaration;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore model) throws CoreException {
			ImportDeclaration node= fImportDeclaration;
			TextEditGroup group= createTextEditGroup(FixMessages.UnusedCodeFix_RemoveImport_description, cuRewrite);
			cuRewrite.getASTRewrite().remove(node, group);
		}

	}

	/**
	 * Removes the unused type parameter.
	 *
	 */
	public static class RemoveUnusedTypeParameterOperation extends CompilationUnitRewriteOperation {
		private final SimpleName fUnusedName;

		public RemoveUnusedTypeParameterOperation(SimpleName unusedName) {
			fUnusedName= unusedName;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore linkedModel) throws CoreException {
			ASTRewrite rewrite= cuRewrite.getASTRewrite();
			IBinding binding= fUnusedName.resolveBinding();
			CompilationUnit root= (CompilationUnit) fUnusedName.getRoot();
			String displayString= FixMessages.UnusedCodeFix_RemoveUnusedTypeParameter_description;
			TextEditGroup group= createTextEditGroup(displayString, cuRewrite);

			if (binding.getKind() == IBinding.TYPE) {
				ITypeBinding decl= ((ITypeBinding) binding).getTypeDeclaration();
				ASTNode declaration= root.findDeclaringNode(decl);
				if (declaration.getParent() instanceof TypeDeclarationStatement) {
					declaration= declaration.getParent();
				}
				rewrite.remove(declaration, group);
			}
		}
	}

	public static class RemoveUnusedMemberOperation extends CompilationUnitRewriteOperation {

		private final SimpleName[] fUnusedNames;
		private boolean fForceRemove;
		private int fRemovedAssignmentsCount;
		private int fAlteredAssignmentsCount;

		public RemoveUnusedMemberOperation(SimpleName[] unusedNames, boolean removeAllAsignements) {
			fUnusedNames= unusedNames;
			fForceRemove= removeAllAsignements;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore model) throws CoreException {
			for (SimpleName unusedName : fUnusedNames) {
				removeUnusedName(cuRewrite, unusedName);
			}
		}

		private void removeUnusedName(CompilationUnitRewrite cuRewrite, SimpleName simpleName) {
			ASTRewrite rewrite= cuRewrite.getASTRewrite();
			CompilationUnit completeRoot= cuRewrite.getRoot();

			IBinding binding= simpleName.resolveBinding();
			CompilationUnit root= (CompilationUnit) simpleName.getRoot();
			String displayString= getDisplayString(binding);
			TextEditGroup group= createTextEditGroup(displayString, cuRewrite);
			if (binding.getKind() == IBinding.METHOD) {
				IMethodBinding decl= ((IMethodBinding) binding).getMethodDeclaration();
				ASTNode declaration= root.findDeclaringNode(decl);
				rewrite.remove(declaration, group);
			} else if (binding.getKind() == IBinding.TYPE) {
				ITypeBinding decl= ((ITypeBinding) binding).getTypeDeclaration();
				ASTNode declaration= root.findDeclaringNode(decl);
				if (declaration.getParent() instanceof TypeDeclarationStatement) {
					declaration= declaration.getParent();
				}
				rewrite.remove(declaration, group);
			} else if (binding.getKind() == IBinding.VARIABLE) {
				SimpleName nameNode= (SimpleName) NodeFinder.perform(completeRoot, simpleName.getStartPosition(), simpleName.getLength());
				SimpleName[] references= LinkedNodeFinder.findByBinding(completeRoot, nameNode.resolveBinding());
				for (int i= 0; i < references.length; i++) {
					removeVariableReferences(rewrite, references[i], group);
				}

				IVariableBinding bindingDecl= ((IVariableBinding) nameNode.resolveBinding()).getVariableDeclaration();
				ASTNode declaringNode= completeRoot.findDeclaringNode(bindingDecl);
				if (declaringNode instanceof SingleVariableDeclaration) {
					removeParamTag(rewrite, (SingleVariableDeclaration) declaringNode, group);
				}
			} else {
				// unexpected
			}
		}

		private String getDisplayString(IBinding binding) {
			switch (binding.getKind()) {
				case IBinding.TYPE:
					return FixMessages.UnusedCodeFix_RemoveUnusedType_description;
				case IBinding.METHOD:
					if (((IMethodBinding) binding).isConstructor()) {
						return FixMessages.UnusedCodeFix_RemoveUnusedConstructor_description;
					} else {
						return FixMessages.UnusedCodeFix_RemoveUnusedPrivateMethod_description;
					}
				case IBinding.VARIABLE:
					if (((IVariableBinding) binding).isField()) {
						return FixMessages.UnusedCodeFix_RemoveUnusedField_description;
					} else {
						return FixMessages.UnusedCodeFix_RemoveUnusedVariabl_description;
					}
				default:
					return ""; //$NON-NLS-1$
			}
		}

		private void removeParamTag(ASTRewrite rewrite, SingleVariableDeclaration varDecl, TextEditGroup group) {
			if (varDecl.getParent() instanceof MethodDeclaration) {
				Javadoc javadoc= ((MethodDeclaration) varDecl.getParent()).getJavadoc();
				if (javadoc != null) {
					TagElement tagElement= JavadocTagsSubProcessorCore.findParamTag(javadoc, varDecl.getName().getIdentifier());
					if (tagElement != null) {
						rewrite.remove(tagElement, group);
					}
				}
			}
		}

		/**
		 * Remove the field or variable declaration including the initializer.
		 * @param rewrite the AST rewriter to use
		 * @param reference a reference to the variable to remove
		 * @param group the text edit group to use
		 */
		private void removeVariableReferences(ASTRewrite rewrite, SimpleName reference, TextEditGroup group) {
			ASTNode parent= reference.getParent();
			while (parent instanceof QualifiedName) {
				parent= parent.getParent();
			}
			if (parent instanceof FieldAccess) {
				parent= parent.getParent();
			}

			int nameParentType= parent.getNodeType();
			if (nameParentType == ASTNode.ASSIGNMENT) {
				Assignment assignment= (Assignment) parent;
				Expression rightHand= assignment.getRightHandSide();

				ASTNode assignParent= assignment.getParent();
				if (assignParent.getNodeType() == ASTNode.EXPRESSION_STATEMENT && rightHand.getNodeType() != ASTNode.ASSIGNMENT) {
					removeVariableWithInitializer(rewrite, rightHand, assignParent, group);
				}	else {
					rewrite.replace(assignment, rewrite.createCopyTarget(rightHand), group);
				}
			} else if (nameParentType == ASTNode.SINGLE_VARIABLE_DECLARATION) {
				rewrite.remove(parent, group);
			} else if (nameParentType == ASTNode.VARIABLE_DECLARATION_FRAGMENT) {
				VariableDeclarationFragment frag= (VariableDeclarationFragment) parent;
				ASTNode varDecl= frag.getParent();
				List<VariableDeclarationFragment> fragments;
				if (varDecl instanceof VariableDeclarationExpression) {
					fragments= ((VariableDeclarationExpression) varDecl).fragments();
				} else if (varDecl instanceof FieldDeclaration) {
					fragments= ((FieldDeclaration) varDecl).fragments();
				} else {
					fragments= ((VariableDeclarationStatement) varDecl).fragments();
				}
				Expression initializer= frag.getInitializer();
				ArrayList<Expression> sideEffects= new ArrayList<>();
				if (initializer != null) {
					initializer.accept(new SideEffectFinder(sideEffects));
				}

				/*
				 * Special case for when the variable initializer is a conditional expression.
				 * Certain actions must be taken depending on where in the conditional the side effect expressions are located.
				 */
				if (initializer instanceof ConditionalExpression && varDecl instanceof VariableDeclarationStatement) {
					AST ast= rewrite.getAST();
					ConditionalExpression ce= (ConditionalExpression) initializer;

					// check if side effects and both expressions are to be removed then we remove whole statement
					if (fForceRemove || (!checkSideEffects(sideEffects) &&
							!checkCondtionalExpression(ce.getThenExpression()) &&
							!checkCondtionalExpression(ce.getElseExpression()))) {
						rewrite.remove(varDecl, group);
						return;
					}

					IfStatement ifStatement= ast.newIfStatement();
					ifStatement.setExpression((Expression) rewrite.createCopyTarget(getExpressionWithoutParenthezis(ce.getExpression())));

					Block thenBlock= ast.newBlock();
					// check if 'then' block contains code to keep
					if (checkCondtionalExpression(ce.getThenExpression())) {
						ASTNode thenExpression= rewrite.createCopyTarget(getExpressionWithoutParenthezis(ce.getThenExpression()));
						thenBlock.statements().add(ast.newExpressionStatement((Expression) thenExpression));
					}
					ifStatement.setThenStatement(thenBlock);

					// check if 'else' block contains code to keep
					if (checkCondtionalExpression(ce.getElseExpression())) {
						Block elseBlock= ast.newBlock();
						ASTNode elseExpression= rewrite.createCopyTarget(getExpressionWithoutParenthezis(ce.getElseExpression()));
						elseBlock.statements().add(ast.newExpressionStatement((Expression) elseExpression));
						ifStatement.setElseStatement(elseBlock);
					}

					rewrite.replace(varDecl, ifStatement, group);
					return;
				}

				boolean sideEffectInitializer= sideEffects.size() > 0;
				if (fragments.size() == fUnusedNames.length) {
					if (fForceRemove) {
						rewrite.remove(varDecl, group);
						return;
					}
					if (parent.getParent() instanceof FieldDeclaration) {
						rewrite.remove(varDecl, group);
						return;
					}
					if (sideEffectInitializer) {
						Statement[] wrapped= new Statement[sideEffects.size()];
						for (int i= 0; i < wrapped.length; i++) {
							Expression sideEffect= sideEffects.get(i);
							Expression movedInit= (Expression) rewrite.createMoveTarget(sideEffect);
							wrapped[i]= rewrite.getAST().newExpressionStatement(movedInit);
						}
						StatementRewrite statementRewrite= new StatementRewrite(rewrite, new ASTNode[] { varDecl });
						statementRewrite.replace(wrapped, group);
					} else {
						rewrite.remove(varDecl, group);
					}
				} else {
					if (fForceRemove) {
						rewrite.remove(frag, group);
						return;
					}
					//multiple declarations in one line
					ASTNode declaration = parent.getParent();
					if (declaration instanceof FieldDeclaration) {
						rewrite.remove(frag, group);
						return;
					}
					if (declaration instanceof VariableDeclarationStatement) {
						splitUpDeclarations(rewrite, group, frag, (VariableDeclarationStatement) declaration, sideEffects);
						rewrite.remove(frag, group);
						return;
					}
					if (declaration instanceof VariableDeclarationExpression) {
						//keep constructors and method invocations
						if (!sideEffectInitializer){
							rewrite.remove(frag, group);
						}
					}
				}
			} else if (nameParentType == ASTNode.POSTFIX_EXPRESSION || nameParentType == ASTNode.PREFIX_EXPRESSION) {
				Expression expression= (Expression)parent;
				ASTNode expressionParent= expression.getParent();
				if (expressionParent.getNodeType() == ASTNode.EXPRESSION_STATEMENT) {
					removeStatement(rewrite, expressionParent, group);
				} else {
					rewrite.remove(expression, group);
				}
			}
		}

		private static Expression getExpressionWithoutParenthezis(Expression expression) {
			Expression currExpression= expression;
			while (currExpression instanceof ParenthesizedExpression) {
				currExpression= ((ParenthesizedExpression) currExpression).getExpression();
			}
			return currExpression;
		}

		/*
		 * Return TRUE if the expression node type is a method, pre/posfix or assignment
		 */
		private static boolean checkCondtionalExpression(Expression expression) {
			int nodeType= getExpressionWithoutParenthezis(expression).getNodeType();
			if (nodeType == ASTNode.METHOD_INVOCATION ||
					nodeType == ASTNode.POSTFIX_EXPRESSION ||
					nodeType == ASTNode.PREFIX_EXPRESSION ||
					nodeType == ASTNode.ASSIGNMENT) {
				return true;
			}
			return false;
		}

		/*
		 * Return TRUE if any of the sideEffects expression node type is a method, pre/posfix or assignment
		 */
		private static boolean checkSideEffects(List<Expression> sideEffects) {
			if (sideEffects.isEmpty()) {
				return false;
			}
			for (Expression expression : sideEffects) {
				if (checkCondtionalExpression(expression)) {
					return true;
				}
			}
			return false;
		}

		private void splitUpDeclarations(ASTRewrite rewrite, TextEditGroup group, VariableDeclarationFragment frag, VariableDeclarationStatement originalStatement, List<Expression> sideEffects) {
			if (sideEffects.size() > 0) {
				ListRewrite statementRewrite= rewrite.getListRewrite(originalStatement.getParent(), (ChildListPropertyDescriptor) originalStatement.getLocationInParent());

				Statement previousStatement= originalStatement;
				for (Expression sideEffect : sideEffects) {
					Expression movedInit= (Expression) rewrite.createMoveTarget(sideEffect);
					ExpressionStatement wrapped= rewrite.getAST().newExpressionStatement(movedInit);
					statementRewrite.insertAfter(wrapped, previousStatement, group);
					previousStatement= wrapped;
				}

				VariableDeclarationStatement newDeclaration= null;
				List<VariableDeclarationFragment> fragments= originalStatement.fragments();
				int fragIndex= fragments.indexOf(frag);
				ListIterator<VariableDeclarationFragment> fragmentIterator= fragments.listIterator(fragIndex+1);
				while (fragmentIterator.hasNext()) {
					VariableDeclarationFragment currentFragment= fragmentIterator.next();
					VariableDeclarationFragment movedFragment= (VariableDeclarationFragment) rewrite.createMoveTarget(currentFragment);
					if (newDeclaration == null) {
						newDeclaration= rewrite.getAST().newVariableDeclarationStatement(movedFragment);
						Type copiedType= (Type) rewrite.createCopyTarget(originalStatement.getType());
						newDeclaration.setType(copiedType);
					} else {
						newDeclaration.fragments().add(movedFragment);
					}
				}
				if (newDeclaration != null){
					statementRewrite.insertAfter(newDeclaration, previousStatement, group);
					if (originalStatement.fragments().size() == newDeclaration.fragments().size() + 1){
						rewrite.remove(originalStatement, group);
					}
				}
			}
		}

		private void removeVariableWithInitializer(ASTRewrite rewrite, ASTNode initializerNode, ASTNode statementNode, TextEditGroup group) {
			boolean performRemove= fForceRemove;
			if (!performRemove) {
				ArrayList<Expression> sideEffectNodes= new ArrayList<>();
				initializerNode.accept(new SideEffectFinder(sideEffectNodes));
				performRemove= sideEffectNodes.isEmpty();
			}
			if (performRemove) {
				removeStatement(rewrite, statementNode, group);
				fRemovedAssignmentsCount++;
			} else {
				ASTNode initNode = rewrite.createMoveTarget(initializerNode);
				ExpressionStatement statement = rewrite.getAST().newExpressionStatement((Expression) initNode);
				rewrite.replace(statementNode, statement, null);
				fAlteredAssignmentsCount++;
			}
		}

		private void removeStatement(ASTRewrite rewrite, ASTNode statementNode, TextEditGroup group) {
			if (ASTNodes.isControlStatementBody(statementNode.getLocationInParent())) {
				rewrite.replace(statementNode, rewrite.getAST().newBlock(), group);
			} else {
				rewrite.remove(statementNode, group);
			}
		}

		@Override
		public String getAdditionalInfo() {
			StringBuilder sb=new StringBuilder();
			if (fRemovedAssignmentsCount == 1) {
				sb.append(FixMessages.UnusedCodeFix_RemoveFieldOrLocal_RemovedAssignments_preview_singular);
			} else if (fRemovedAssignmentsCount > 1) {
				sb.append(Messages.format(FixMessages.UnusedCodeFix_RemoveFieldOrLocal_RemovedAssignments_preview_plural, String.valueOf(fRemovedAssignmentsCount)));
			}
			if (fAlteredAssignmentsCount == 1) {
				sb.append(FixMessages.UnusedCodeFix_RemoveFieldOrLocal_AlteredAssignments_preview_singular);
			} else if (fAlteredAssignmentsCount > 1) {
				sb.append(Messages.format(FixMessages.UnusedCodeFix_RemoveFieldOrLocal_AlteredAssignments_preview_plural, String.valueOf(fAlteredAssignmentsCount)));
			}
			if (sb.length()>0) {
				return sb.toString();
			} else
				return null;
		}
	}

	public static class RemoveCastOperation extends CompilationUnitRewriteOperation {

		private final CastExpression fCast;

		public RemoveCastOperation(CastExpression cast) {
			fCast= cast;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore model) throws CoreException {

			TextEditGroup group= createTextEditGroup(FixMessages.UnusedCodeFix_RemoveCast_description, cuRewrite);

			ASTRewrite rewrite= cuRewrite.getASTRewrite();

			CastExpression cast= fCast;
			Expression expression= cast.getExpression();
			if (expression instanceof ParenthesizedExpression) {
				Expression childExpression= ((ParenthesizedExpression) expression).getExpression();
				if (NecessaryParenthesesChecker.needsParentheses(childExpression, cast, CastExpression.EXPRESSION_PROPERTY)) {
					expression= childExpression;
				}
			}

			replaceCast(cast, expression, rewrite, group);
		}
	}

	public static class RemoveAllCastOperation extends CompilationUnitRewriteOperation {

		private final LinkedHashSet<CastExpression> fUnnecessaryCasts;

		public RemoveAllCastOperation(LinkedHashSet<CastExpression> unnecessaryCasts) {
			fUnnecessaryCasts= unnecessaryCasts;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore model) throws CoreException {
			ASTRewrite rewrite= cuRewrite.getASTRewrite();

			TextEditGroup group= createTextEditGroup(FixMessages.UnusedCodeFix_RemoveCast_description, cuRewrite);

			while (fUnnecessaryCasts.size() > 0) {
				CastExpression castExpression= fUnnecessaryCasts.iterator().next();
				fUnnecessaryCasts.remove(castExpression);

				/*
				 * ASTRewrite doesn't allow replacing (deleting) of moved nodes. To solve problems
				 * with nested casts, we need to replace all casts at once.
				 *
				 * The loop proceeds downwards to find the innermost expression that stays in the result (downChild)
				 * and it also skips necessary parentheses.
				 */
				CastExpression down= castExpression;
				Expression downChild= down.getExpression();
				while (true) {
					if (fUnnecessaryCasts.contains(downChild)) {
						down= (CastExpression) downChild;
						fUnnecessaryCasts.remove(down);
						downChild= down.getExpression();
					} else if (downChild instanceof ParenthesizedExpression) {
						Expression downChildExpression= ((ParenthesizedExpression) downChild).getExpression();
						// is it justified that downChild is a ParenthesizedExpression?
						if (NecessaryParenthesesChecker.needsParentheses(downChildExpression, down, CastExpression.EXPRESSION_PROPERTY)) {
							// yes => continue walking down
							downChild= downChildExpression;
						} else {
							// no => stop walking
							break;
						}
					} else {
						break;
					}
				}

				// downChild is the innermost CastExpression's expression, stripped of a necessary surrounding ParenthesizedExpression
				// Move either downChild (if it doesn't need parentheses), or a parenthesized version if necessary

				replaceCast(castExpression, downChild, rewrite, group);
			}
		}
	}

	public static UnusedCodeFixCore createRemoveUnusedImportFix(CompilationUnit compilationUnit, IProblemLocationCore problem) {
		if (isUnusedImport(problem)) {
			ImportDeclaration node= getImportDeclaration(problem, compilationUnit);
			if (node != null) {
				String label= FixMessages.UnusedCodeFix_RemoveImport_description;
				RemoveImportOperation operation= new RemoveImportOperation(node);
				Map<String, String> options= new Hashtable<>();
				options.put(CleanUpConstants.REMOVE_UNUSED_CODE_IMPORTS, CleanUpOptionsCore.TRUE);
				return new UnusedCodeFixCore(label, compilationUnit, new CompilationUnitRewriteOperation[] {operation}, options);
			}
		}
		return null;
	}

	public static boolean isUnusedImport(IProblemLocationCore problem) {
		int id= problem.getProblemId();
		return id == IProblem.UnusedImport || id == IProblem.DuplicateImport || id == IProblem.ConflictingImport || id == IProblem.CannotImportPackage || id == IProblem.ImportNotFound;
	}

	public static UnusedCodeFixCore createUnusedMemberFix(CompilationUnit compilationUnit, IProblemLocationCore problem, boolean removeAllAssignements) {
		if (isUnusedMember(problem)) {
			SimpleName name= getUnusedName(compilationUnit, problem);
			if (name != null) {
				IBinding binding= name.resolveBinding();
				if (binding != null) {
					if (isFormalParameterInEnhancedForStatement(name))
						return null;

					String label= getDisplayString(name, binding, removeAllAssignements);
					RemoveUnusedMemberOperation operation= new RemoveUnusedMemberOperation(new SimpleName[] { name }, removeAllAssignements);
					return new UnusedCodeFixCore(label, compilationUnit, new CompilationUnitRewriteOperation[] { operation }, getCleanUpOptions(binding, removeAllAssignements));
				}
			}
		}
		return null;
	}

	public static UnusedCodeFixCore createUnusedTypeParameterFix(CompilationUnit compilationUnit, IProblemLocationCore problemLoc) {
		if (problemLoc.getProblemId() == IProblem.UnusedTypeParameter) {
			SimpleName name= getUnusedName(compilationUnit, problemLoc);
			if (name != null) {
				IBinding binding= name.resolveBinding();
				if (binding != null) {
					String label= FixMessages.UnusedCodeFix_RemoveUnusedTypeParameter_description;
					RemoveUnusedTypeParameterOperation operation= new RemoveUnusedTypeParameterOperation(name);
					return new UnusedCodeFixCore(label, compilationUnit, new CompilationUnitRewriteOperation[] { operation }, getCleanUpOptions(binding, false));
				}
			}
		}
		return null;
	}

	public static boolean isUnusedMember(IProblemLocationCore problem) {
		int id= problem.getProblemId();
		return id == IProblem.UnusedPrivateMethod || id == IProblem.UnusedPrivateConstructor || id == IProblem.UnusedPrivateField || id == IProblem.UnusedPrivateType
				|| id == IProblem.LocalVariableIsNeverUsed || id == IProblem.ArgumentIsNeverUsed;
	}

	public static UnusedCodeFixCore createRemoveUnusedCastFix(CompilationUnit compilationUnit, IProblemLocationCore problem) {
		if (problem.getProblemId() != IProblem.UnnecessaryCast)
			return null;

		ASTNode selectedNode= problem.getCoveringNode(compilationUnit);

		ASTNode curr= selectedNode;
		while (curr instanceof ParenthesizedExpression) {
			curr= ((ParenthesizedExpression) curr).getExpression();
		}

		if (!(curr instanceof CastExpression))
			return null;

		return new UnusedCodeFixCore(FixMessages.UnusedCodeFix_RemoveCast_description, compilationUnit, new CompilationUnitRewriteOperation[] {new RemoveCastOperation((CastExpression)curr)});
	}

	public static ICleanUpFixCore createCleanUp(CompilationUnit compilationUnit,
			boolean removeUnusedPrivateMethods,
			boolean removeUnusedPrivateConstructors,
			boolean removeUnusedPrivateFields,
			boolean removeUnusedPrivateTypes,
			boolean removeUnusedLocalVariables,
			boolean removeUnusedImports,
			boolean removeUnusedCast) {

		IProblem[] problems= compilationUnit.getProblems();
		IProblemLocationCore[] locations= new IProblemLocationCore[problems.length];
		for (int i= 0; i < problems.length; i++) {
			locations[i]= new ProblemLocationCore(problems[i]);
		}

		return createCleanUp(compilationUnit, locations,
				removeUnusedPrivateMethods,
				removeUnusedPrivateConstructors,
				removeUnusedPrivateFields,
				removeUnusedPrivateTypes,
				removeUnusedLocalVariables,
				removeUnusedImports,
				removeUnusedCast);
	}

	public static ICleanUpFixCore createCleanUp(CompilationUnit compilationUnit, IProblemLocationCore[] problems,
			boolean removeUnusedPrivateMethods,
			boolean removeUnusedPrivateConstructors,
			boolean removeUnusedPrivateFields,
			boolean removeUnusedPrivateTypes,
			boolean removeUnusedLocalVariables,
			boolean removeUnusedImports,
			boolean removeUnusedCast) {

		List<CompilationUnitRewriteOperation> result= new ArrayList<>();
		Hashtable<ASTNode, List<SimpleName>> variableDeclarations= new Hashtable<>();
		LinkedHashSet<CastExpression> unnecessaryCasts= new LinkedHashSet<>();
		for (IProblemLocationCore problem : problems) {
			int id= problem.getProblemId();

			if (removeUnusedImports && (id == IProblem.UnusedImport || id == IProblem.DuplicateImport || id == IProblem.ConflictingImport ||
				    id == IProblem.CannotImportPackage || id == IProblem.ImportNotFound))
			{
				ImportDeclaration node= UnusedCodeFixCore.getImportDeclaration(problem, compilationUnit);
				if (node != null) {
					result.add(new RemoveImportOperation(node));
				}
			}

			if ((removeUnusedPrivateMethods && id == IProblem.UnusedPrivateMethod) || (removeUnusedPrivateConstructors && id == IProblem.UnusedPrivateConstructor) ||
			    (removeUnusedPrivateTypes && id == IProblem.UnusedPrivateType)) {

				SimpleName name= getUnusedName(compilationUnit, problem);
				if (name != null) {
					IBinding binding= name.resolveBinding();
					if (binding != null) {
						result.add(new RemoveUnusedMemberOperation(new SimpleName[] {name}, false));
					}
				}
			}

			if ((removeUnusedLocalVariables && id == IProblem.LocalVariableIsNeverUsed) ||  (removeUnusedPrivateFields && id == IProblem.UnusedPrivateField)) {
				SimpleName name= getUnusedName(compilationUnit, problem);
				if (name != null) {
					IBinding binding= name.resolveBinding();
					if (binding instanceof IVariableBinding && !isFormalParameterInEnhancedForStatement(name) && (!((IVariableBinding) binding).isField() || isSideEffectFree(name, compilationUnit))) {
						VariableDeclarationFragment parent= ASTNodes.getParent(name, VariableDeclarationFragment.class);
						if (parent != null) {
							ASTNode varDecl= parent.getParent();
							if (!variableDeclarations.containsKey(varDecl)) {
								variableDeclarations.put(varDecl, new ArrayList<SimpleName>());
							}
							variableDeclarations.get(varDecl).add(name);
						} else {
							result.add(new RemoveUnusedMemberOperation(new SimpleName[] {name}, false));
						}
					}
				}
			}

			if (removeUnusedCast && id == IProblem.UnnecessaryCast) {
				ASTNode selectedNode= problem.getCoveringNode(compilationUnit);

				ASTNode curr= selectedNode;
				while (curr instanceof ParenthesizedExpression) {
					curr= ((ParenthesizedExpression) curr).getExpression();
				}

				if (curr instanceof CastExpression) {
					unnecessaryCasts.add((CastExpression) curr);
				}
			}
		}
		for (ASTNode node : variableDeclarations.keySet()) {
			List<SimpleName> names= variableDeclarations.get(node);
			result.add(new RemoveUnusedMemberOperation(names.toArray(new SimpleName[0]), false));
		}
		if (unnecessaryCasts.size() > 0)
			result.add(new RemoveAllCastOperation(unnecessaryCasts));

		if (result.isEmpty())
			return null;

		return new UnusedCodeFixCore(FixMessages.UnusedCodeFix_change_name, compilationUnit, result.toArray(new CompilationUnitRewriteOperation[result.size()]));
	}

	public static boolean isFormalParameterInEnhancedForStatement(SimpleName name) {
		return name.getParent() instanceof SingleVariableDeclaration && name.getParent().getLocationInParent() == EnhancedForStatement.PARAMETER_PROPERTY;
	}

	public static boolean isSideEffectFree(SimpleName simpleName, CompilationUnit completeRoot) {
		SimpleName nameNode= (SimpleName) NodeFinder.perform(completeRoot, simpleName.getStartPosition(), simpleName.getLength());
		SimpleName[] references= LinkedNodeFinder.findByBinding(completeRoot, nameNode.resolveBinding());
		for (SimpleName reference : references) {
			if (hasSideEffect(reference))
				return false;
		}
		return true;
	}

	private static boolean hasSideEffect(SimpleName reference) {
		ASTNode parent= reference.getParent();
		while (parent instanceof QualifiedName) {
			parent= parent.getParent();
		}
		if (parent instanceof FieldAccess) {
			parent= parent.getParent();
		}

		ASTNode node= null;
		int nameParentType= parent.getNodeType();
		if (nameParentType == ASTNode.ASSIGNMENT) {
			Assignment assignment= (Assignment) parent;
			node= assignment.getRightHandSide();
		} else if (nameParentType == ASTNode.SINGLE_VARIABLE_DECLARATION) {
			SingleVariableDeclaration decl= (SingleVariableDeclaration)parent;
			node= decl.getInitializer();
			if (node == null)
				return false;
		} else if (nameParentType == ASTNode.VARIABLE_DECLARATION_FRAGMENT) {
			node= parent;
		} else {
			return false;
		}

		ArrayList<Expression> sideEffects= new ArrayList<>();
		node.accept(new SideEffectFinder(sideEffects));
		return sideEffects.size() > 0;
	}

	public static SimpleName getUnusedName(CompilationUnit compilationUnit, IProblemLocationCore problem) {
		ASTNode selectedNode= problem.getCoveringNode(compilationUnit);

		if (selectedNode instanceof MethodDeclaration) {
			return ((MethodDeclaration) selectedNode).getName();
		} else if (selectedNode instanceof SimpleName) {
			return (SimpleName) selectedNode;
		}

		return null;
	}

	public static String getDisplayString(SimpleName simpleName, IBinding binding, boolean removeAllAssignements) {
		String name= BasicElementLabels.getJavaElementName(simpleName.getIdentifier());
		switch (binding.getKind()) {
			case IBinding.TYPE:
				return Messages.format(FixMessages.UnusedCodeFix_RemoveType_description, name);
			case IBinding.METHOD:
				if (((IMethodBinding) binding).isConstructor()) {
					return Messages.format(FixMessages.UnusedCodeFix_RemoveConstructor_description, name);
				} else {
					return Messages.format(FixMessages.UnusedCodeFix_RemoveMethod_description, name);
				}
			case IBinding.VARIABLE:
				if (removeAllAssignements) {
					return Messages.format(FixMessages.UnusedCodeFix_RemoveFieldOrLocalWithInitializer_description, name);
				} else {
					return Messages.format(FixMessages.UnusedCodeFix_RemoveFieldOrLocal_description, name);
				}
			default:
				return ""; //$NON-NLS-1$
		}
	}

	public static Map<String, String> getCleanUpOptions(IBinding binding, boolean removeAll) {
		Map<String, String> result= new Hashtable<>();

		result.put(CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_MEMBERS, CleanUpOptionsCore.TRUE);
		switch (binding.getKind()) {
			case IBinding.TYPE:
				result.put(CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_TYPES, CleanUpOptionsCore.TRUE);
				break;
			case IBinding.METHOD:
				if (((IMethodBinding) binding).isConstructor()) {
					result.put(CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_CONSTRUCTORS, CleanUpOptionsCore.TRUE);
				} else {
					result.put(CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_METHODS, CleanUpOptionsCore.TRUE);
				}
				break;
			case IBinding.VARIABLE:
				if (removeAll)
					return null;

				result.put(CleanUpConstants.REMOVE_UNUSED_CODE_PRIVATE_FELDS, CleanUpOptionsCore.TRUE);
				result.put(CleanUpConstants.REMOVE_UNUSED_CODE_LOCAL_VARIABLES, CleanUpOptionsCore.TRUE);
				break;
			default:
				break;
		}

		return result;
	}

	public static ImportDeclaration getImportDeclaration(IProblemLocationCore problem, CompilationUnit compilationUnit) {
		ASTNode selectedNode= problem.getCoveringNode(compilationUnit);
		if (selectedNode != null) {
			ASTNode node= ASTNodes.getParent(selectedNode, ASTNode.IMPORT_DECLARATION);
			if (node instanceof ImportDeclaration) {
				return (ImportDeclaration)node;
			}
		}
		return null;
	}

	private static void replaceCast(CastExpression castExpression, Expression replacement, ASTRewrite rewrite, TextEditGroup group) {
		boolean castEnclosedInNecessaryParentheses= castExpression.getParent() instanceof ParenthesizedExpression
				&& NecessaryParenthesesChecker.needsParentheses(castExpression, castExpression.getParent().getParent(), castExpression.getParent().getLocationInParent());

		ASTNode toReplace= castEnclosedInNecessaryParentheses ? castExpression.getParent() : castExpression;
		ASTNode move;
		if (NecessaryParenthesesChecker.needsParentheses(replacement, toReplace.getParent(), toReplace.getLocationInParent())) {
			if (replacement.getParent() instanceof ParenthesizedExpression) {
				move= rewrite.createMoveTarget(replacement.getParent());
			} else if (castEnclosedInNecessaryParentheses) {
				toReplace= castExpression;
				move= rewrite.createMoveTarget(replacement);
			} else {
				ParenthesizedExpression parentheses= replacement.getAST().newParenthesizedExpression();
				parentheses.setExpression((Expression) rewrite.createMoveTarget(replacement));
				move= parentheses;
			}
		} else {
			move= rewrite.createMoveTarget(replacement);
		}
		rewrite.replace(toReplace, move, group);
	}

	private final Map<String, String> fCleanUpOptions;

	private UnusedCodeFixCore(String name, CompilationUnit compilationUnit, CompilationUnitRewriteOperation[] fixRewriteOperations) {
		this(name, compilationUnit, fixRewriteOperations, null);
	}

	private UnusedCodeFixCore(String name, CompilationUnit compilationUnit, CompilationUnitRewriteOperation[] fixRewriteOperations, Map<String, String> options) {
		super(name, compilationUnit, fixRewriteOperations);
		fCleanUpOptions= options;
	}

	public UnusedCodeCleanUpCore getCleanUp() {
		if (fCleanUpOptions == null)
			return null;

		return new UnusedCodeCleanUpCore(fCleanUpOptions);
	}

}