/* * (c) Copyright IBM Corp. 2000, 2001. * All Rights Reserved. */package org.eclipse.jdt.internal.ui.launcher;import java.io.File;import java.lang.reflect.InvocationTargetException;import java.util.ArrayList;import java.util.Iterator;import java.util.List;import org.eclipse.core.resources.IWorkspace;import org.eclipse.core.resources.IWorkspaceDescription;import org.eclipse.core.resources.IWorkspaceRunnable;import org.eclipse.core.runtime.CoreException;import org.eclipse.core.runtime.IPath;import org.eclipse.core.runtime.IProgressMonitor;import org.eclipse.core.runtime.Path;import org.eclipse.jdt.core.JavaCore;import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;import org.eclipse.jdt.internal.ui.JavaPlugin;import org.eclipse.jdt.internal.ui.preferences.ClasspathVariablesPreferencePage;import org.eclipse.jdt.internal.ui.util.ExceptionHandler;import org.eclipse.jdt.launching.IVMInstall;import org.eclipse.jdt.launching.IVMInstallType;import org.eclipse.jdt.launching.JavaRuntime;import org.eclipse.jdt.launching.LibraryLocation;import org.eclipse.jface.dialogs.ProgressMonitorDialog;import org.eclipse.jface.preference.PreferencePage;import org.eclipse.jface.viewers.CheckStateChangedEvent;import org.eclipse.jface.viewers.CheckboxTableViewer;import org.eclipse.jface.viewers.ColumnWeightData;import org.eclipse.jface.viewers.ICheckStateListener;import org.eclipse.jface.viewers.ISelectionChangedListener;import org.eclipse.jface.viewers.IStructuredSelection;import org.eclipse.jface.viewers.SelectionChangedEvent;import org.eclipse.jface.viewers.TableLayout;import org.eclipse.jface.viewers.Viewer;import org.eclipse.jface.viewers.ViewerSorter;import org.eclipse.swt.SWT;import org.eclipse.swt.layout.GridData;import org.eclipse.swt.layout.GridLayout;import org.eclipse.swt.widgets.Button;import org.eclipse.swt.widgets.Composite;import org.eclipse.swt.widgets.Control;import org.eclipse.swt.widgets.Event;import org.eclipse.swt.widgets.Label;import org.eclipse.swt.widgets.Listener;import org.eclipse.swt.widgets.Table;import org.eclipse.swt.widgets.TableColumn;import org.eclipse.swt.widgets.Text;import org.eclipse.ui.IWorkbench;import org.eclipse.ui.IWorkbenchPreferencePage;import org.eclipse.ui.actions.WorkspaceModifyOperation;import org.eclipse.ui.help.DialogPageContextComputer;import org.eclipse.ui.help.WorkbenchHelp;/* * The page for setting the default java runtime preference. */public class VMPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {		private CheckboxTableViewer fVMList;	private Button fAddButton;	private Button fRemoveButton;	private Button fEditButton;		protected Text fJreLib;	protected Text fJreSource;	protected Text fPkgRoot;		private IVMInstallType[] fVMTypes;	private List fVMStandins;	private List fRemovedVMs;		private IPath[] fClasspathVariables= new IPath[3];	public VMPreferencePage() {		super();		setDescription("Create, remove or edit JRE definitions.\nThe checked JRE will be used by default to build and run Java programs");	}		/**	 * @see IWorkbenchPreferencePage#init	 */	public void init(IWorkbench workbench) {	}		private List createFakeVMInstalls(IVMInstallType[] vmTypes) {		ArrayList vms= new ArrayList();		for (int i= 0; i < vmTypes.length; i++) {			IVMInstall[] vmInstalls= vmTypes[i].getVMInstalls();			for (int j= 0; j < vmInstalls.length; j++) 				vms.add(new VMStandin(vmInstalls[j]));		}		return vms;	}		private void initDefaultVM(List fakeVMs) {		IVMInstall realDefault= JavaRuntime.getDefaultVMInstall();		if (realDefault != null) {			Iterator iter= fakeVMs.iterator();			while (iter.hasNext()) {				IVMInstall fakeVM= (IVMInstall)iter.next();				if (isSameVM(fakeVM, realDefault)) {					setDefaultVM(fakeVM);					break;				}			}		}	}		/**	 * @see PreferencePage#createContents(Composite)	 */	protected Control createContents(Composite ancestor) {		fVMTypes= JavaRuntime.getVMInstallTypes();		fVMStandins= createFakeVMInstalls(fVMTypes);		fRemovedVMs= new ArrayList();		noDefaultAndApplyButton();				Composite parent= new Composite(ancestor, SWT.NULL);		GridLayout layout= new GridLayout();		layout.numColumns= 2;		parent.setLayout(layout);								fVMList= new CheckboxTableViewer(parent, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);		GridData gd= new GridData(GridData.FILL_BOTH);		gd.widthHint= convertWidthInCharsToPixels(80);		fVMList.getTable().setLayoutData(gd);				fVMList.setSorter(new ViewerSorter() {			public int compare(Viewer viewer, Object e1, Object e2) {				if ((e1 instanceof IVMInstall) && (e2 instanceof IVMInstall)) {					IVMInstall left= (IVMInstall)e1;					IVMInstall right= (IVMInstall)e2;					String leftType= left.getVMInstallType().getName();					String rightType= right.getVMInstallType().getName();					int res= leftType.compareToIgnoreCase(rightType);					if (res != 0)						return res;					return left.getName().compareToIgnoreCase(right.getName());				}				return super.compare(viewer, e1, e2);			}						public boolean isSorterProperty(Object element, String property) {				return true;			}		});				Table table= fVMList.getTable();				table.setHeaderVisible(true);		table.setLinesVisible(true);				TableLayout tableLayout= new TableLayout();		table.setLayout(tableLayout);				TableColumn column1= table.getColumn(0);		column1.setText("JRE Type");			TableColumn column2= new TableColumn(table, SWT.NULL);		column2.setText("Name");				TableColumn column3= new TableColumn(table, SWT.NULL);		column3.setText("Location");				tableLayout.addColumnData(new ColumnWeightData(30));		tableLayout.addColumnData(new ColumnWeightData(30));		tableLayout.addColumnData(new ColumnWeightData(50));				fVMList.setLabelProvider(new VMLabelProvider());		fVMList.setContentProvider(new ListContentProvider(fVMList, fVMStandins));				fVMList.addSelectionChangedListener(new ISelectionChangedListener() {			public void selectionChanged(SelectionChangedEvent evt) {				enableButtons();			}		});				fVMList.addCheckStateListener(new ICheckStateListener() {			public void checkStateChanged(CheckStateChangedEvent event) {				IVMInstall vm=  (IVMInstall)event.getElement();				if (event.getChecked())					setDefaultVM(vm);				fVMList.setCheckedElements(new Object[] { vm });			}		});				Composite buttons= new Composite(parent, SWT.NULL);		buttons.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));		buttons.setLayout(new GridLayout());				fAddButton= new Button(buttons, SWT.PUSH);		fAddButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));		fAddButton.setText("Add...");		fAddButton.addListener(SWT.Selection, new Listener() {			public void handleEvent(Event evt) {				addVM();			}		});						fRemoveButton= new Button(buttons, SWT.PUSH);		fRemoveButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));		fRemoveButton.setText("Remove...");		fRemoveButton.addListener(SWT.Selection, new Listener() {			public void handleEvent(Event evt) {				removeVMs();			}		});		fEditButton= new Button(buttons, SWT.PUSH);		fEditButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));		fEditButton.setText("Edit...");		fEditButton.addListener(SWT.Selection, new Listener() {			public void handleEvent(Event evt) {				editVM();			}		});				Composite jreVarsContainer= new Composite(parent, SWT.NULL);		jreVarsContainer.setLayoutData(new GridData(GridData.FILL_BOTH));		GridLayout jreLayout= new GridLayout();		jreLayout.numColumns= 2;		jreVarsContainer.setLayout(jreLayout);								Label l= new Label(jreVarsContainer, SWT.NULL);		l.setText(ClasspathVariablesPreferencePage.JRELIB_VARIABLE);		l.setLayoutData(new GridData());				fJreLib= new Text(jreVarsContainer, SWT.READ_ONLY | SWT.BORDER);		fJreLib.setLayoutData(new GridData(gd.FILL_HORIZONTAL));		 		l= new Label(jreVarsContainer, SWT.NULL);		l.setText(ClasspathVariablesPreferencePage.JRESRC_VARIABLE);		l.setLayoutData(new GridData());				fJreSource= new Text(jreVarsContainer, SWT.READ_ONLY | SWT.BORDER);		fJreSource.setLayoutData(new GridData(gd.FILL_HORIZONTAL));		l= new Label(jreVarsContainer, SWT.NULL);		l.setText(ClasspathVariablesPreferencePage.JRESRCROOT_VARIABLE);		l.setLayoutData(new GridData());				fPkgRoot= new Text(jreVarsContainer, SWT.READ_ONLY | SWT.BORDER);		fPkgRoot.setLayoutData(new GridData(gd.FILL_HORIZONTAL));				fVMList.setInput(JavaRuntime.getVMInstallTypes());		initDefaultVM(fVMStandins);		enableButtons();		WorkbenchHelp.setHelp(parent, new DialogPageContextComputer(this, IJavaHelpContextIds.JRE_PREFERENCE_PAGE));				return parent;	}		protected boolean isDuplicateName(IVMInstallType type, String name) {		for (int i= 0; i < fVMStandins.size(); i++) {			IVMInstall vm= (IVMInstall)fVMStandins.get(i);			if (vm.getVMInstallType() == type) {				if (vm.getName().equals(name))					return true;			}		}		return false;	}				private void addVM() {		AddVMDialog dialog= new AddVMDialog(this, fVMTypes, fVMTypes[0]);		dialog.setTitle("Add JRE");		if (dialog.open() != dialog.OK)			return;		fVMList.refresh();		updateJREVariables(getCurrentDefaultVM());	}		protected void vmAdded(IVMInstall vm) {		fVMStandins.add(vm);		fVMList.refresh();		if (getCurrentDefaultVM() == null)			setDefaultVM(vm);	}		private void removeVMs() {		IStructuredSelection selection= (IStructuredSelection)fVMList.getSelection();		Iterator elements= selection.iterator();		while (elements.hasNext()) {			Object o= elements.next();			fRemovedVMs.add(o);			fVMStandins.remove(o);		}		fVMList.refresh();	}			// editing	private void editVM() {		IStructuredSelection selection= (IStructuredSelection)fVMList.getSelection();		// assume it's length one, otherwise this will not be called		IVMInstall vm= (IVMInstall)selection.getFirstElement();		editVM(vm);	}		private void editVM(IVMInstall vm) {		EditVMDialog dialog= new EditVMDialog(this, fVMTypes, vm);		dialog.setTitle("Edit JRE");		if (dialog.open() != dialog.OK)			return;		fVMList.refresh(vm);		if (isSameVM(JavaRuntime.getDefaultVMInstall(), vm))			updateJREVariables(vm);	}		private boolean isSameVM(IVMInstall left, IVMInstall right) {		if (left == right)			return true;		if (left != null && right != null)			return left.getId().equals(right.getId());		return false;	}	public boolean performOk() {		try {			commitVMInstalls();			JavaRuntime.saveVMConfiguration();			setClassPathVariables();		} catch (CoreException e) {			ExceptionHandler.handle(e, "JRE Configuration", "An exception occurred while saving configuration data");		}		return super.performOk();	}		private void commitVMInstalls() {		for (int i= 0; i < fRemovedVMs.size(); i++) {			VMStandin standin= (VMStandin)fRemovedVMs.get(i);			standin.getVMInstallType().disposeVMInstall(standin.getId());		}				for (int i= 0; i < fVMStandins.size(); i++) {			VMStandin standin= (VMStandin)fVMStandins.get(i);			standin.convertToRealVM();		}				IVMInstall fakeDefault= getCurrentDefaultVM();		if (fakeDefault != null) {			IVMInstallType defaultType= fakeDefault.getVMInstallType();			IVMInstall realDefault= defaultType.findVMInstall(fakeDefault.getId());			JavaRuntime.setDefaultVMInstall(realDefault);		}	}		private IVMInstall getCurrentDefaultVM() {		Object[] checked= fVMList.getCheckedElements();		if (checked.length > 0)			return (IVMInstall)checked[0];		return null;	}		private void vmSelectionChanged() {		enableButtons();	}	private void enableButtons() {		fAddButton.setEnabled(fVMTypes.length > 0);		int selectionCount= ((IStructuredSelection)fVMList.getSelection()).size();		fEditButton.setEnabled(selectionCount == 1);		fRemoveButton.setEnabled(selectionCount > 0);	}		private void setDefaultVM(IVMInstall vm) {		if (vm != null) {			fVMList.setCheckedElements(new Object[] { vm });		} else {			fVMList.setCheckedElements(new Object[0]);		}		updateJREVariables(vm);	}		private static LibraryLocation getAdjustedLocation(IVMInstall defaultVM)  {		String jreLib= "";		String jreSrc= "";		String pkgPath= "";		LibraryLocation location= defaultVM.getLibraryLocation();		if (location == null) {			location= defaultVM.getVMInstallType().getDefaultLibraryLocation(defaultVM.getInstallLocation());			pkgPath= location.getPackageRootPath().toString();					File jreLibFile= location.getSystemLibrary();			if (jreLibFile.isFile())				jreLib= jreLibFile.getAbsolutePath();			else				jreLib= "";							File jreSourceFile= location.getSystemLibrarySource();			if (jreSourceFile.isFile())				jreSrc= jreSourceFile.getAbsolutePath();			else {				jreSrc= "";				pkgPath= "";			}		} else {			jreLib= location.getSystemLibrary().getAbsolutePath();			jreSrc= location.getSystemLibrarySource().getAbsolutePath();			pkgPath= location.getPackageRootPath().toString();		}		return new LibraryLocation(new File(jreLib), new File(jreSrc), new Path(pkgPath));	}		private void updateJREVariables(IVMInstall defaultVM) {		String jreLib= "";		String jreSrc= "";		String pkgPath= "";		if (defaultVM != null) {			LibraryLocation location= getAdjustedLocation(defaultVM);			jreLib= location.getSystemLibrary().getPath();			jreSrc= location.getSystemLibrarySource().getPath();			pkgPath= location.getPackageRootPath().toString();		} else {			IPath lib= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRELIB_VARIABLE);			if (lib != null) {				if (lib.isEmpty())					jreLib= "";				else 					jreLib= lib.toOSString();			}			IPath src= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRESRC_VARIABLE);			if (src != null) {				if (src.isEmpty())					jreSrc= "";				else 					jreSrc= src.toFile().toString();			}			IPath pkgRoot= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRESRCROOT_VARIABLE);			if (pkgRoot != null)				pkgPath= pkgRoot.toString();		}		fJreLib.setText(jreLib);		fJreSource.setText(jreSrc);		fPkgRoot.setText(pkgPath);	}		private void setClassPathVariables() throws CoreException {		File jreLib= new File(fJreLib.getText());		File jreSrc= new File(fJreSource.getText());		Path p= new Path(fPkgRoot.getText());		if (!jreLib.isFile())			jreLib= new File("");		if (!jreSrc.isFile()) {			jreSrc= new File("");			p= new Path("");		}		setClassPathVariables(new LibraryLocation(jreLib, jreSrc, p));	}		private void setClassPathVariables(final LibraryLocation desc) throws CoreException {				ProgressMonitorDialog dialog= new ProgressMonitorDialog(getShell());				try {			dialog.run(true, true, new WorkspaceModifyOperation() {				public void execute(IProgressMonitor pm) throws InvocationTargetException{					try {						doSetClasspathVariables(desc, pm);					} catch (CoreException e) {						throw new InvocationTargetException(e);					}				}			});		} catch (InterruptedException e) {		} catch (InvocationTargetException e) {			throw (CoreException)e.getTargetException();		}	}		private static void doSetClasspathVariables(LibraryLocation desc, IProgressMonitor pm) throws CoreException {		IPath oldLibrary= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRELIB_VARIABLE);		IPath oldSource= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRESRC_VARIABLE);		IPath oldPkgRoot= JavaCore.getClasspathVariable(ClasspathVariablesPreferencePage.JRESRCROOT_VARIABLE);		IPath library= new Path(desc.getSystemLibrary().getPath());		IPath source= new Path(desc.getSystemLibrarySource().getPath());		IPath pkgRoot= desc.getPackageRootPath();			if (!library.equals(oldLibrary))				JavaCore.setClasspathVariable(ClasspathVariablesPreferencePage.JRELIB_VARIABLE, library, pm);			if (!source.equals(oldSource))				JavaCore.setClasspathVariable(ClasspathVariablesPreferencePage.JRESRC_VARIABLE, source, pm);			if (!pkgRoot.equals(oldPkgRoot))				JavaCore.setClasspathVariable(ClasspathVariablesPreferencePage.JRESRCROOT_VARIABLE, pkgRoot, pm);	}		public static void initializeVMInstall() throws CoreException {		boolean wasAutobuild= false;		try {			wasAutobuild= isAutobuild();			if (wasAutobuild) {				setAutobuild(false);			}		} catch (CoreException e) {			wasAutobuild= false;		}		try {			IVMInstall defaultVM= JavaRuntime.getDefaultVMInstall();			if (defaultVM == null) {				defaultVM= getFirstVMInstall();				if (defaultVM != null)					JavaRuntime.setDefaultVMInstall(defaultVM);			}			IVMInstall vmInstall= JavaRuntime.getDefaultVMInstall();			if (vmInstall != null) {				LibraryLocation desc= getAdjustedLocation(vmInstall);				IWorkspace ws= JavaPlugin.getWorkspace();				final LibraryLocation desc2= desc;								ws.run(new IWorkspaceRunnable() {					public void run(IProgressMonitor monitor) throws CoreException {						doSetClasspathVariables(desc2, null);					}				}, null);			}		} finally {			if (wasAutobuild) {				try {					setAutobuild(true);				} catch (CoreException e) {				}			}		}	}		private static void setAutobuild(boolean on) throws CoreException {		IWorkspace ws= JavaPlugin.getWorkspace();		IWorkspaceDescription wsDescription= ws.getDescription();		wsDescription.setAutoBuilding(on);		ws.setDescription(wsDescription);	}	private static boolean isAutobuild() throws CoreException {		IWorkspace ws= JavaPlugin.getWorkspace();		IWorkspaceDescription wsDescription= ws.getDescription();		return wsDescription.isAutoBuilding();	}	private static IVMInstall getFirstVMInstall() {		IVMInstallType[] vmTypes= JavaRuntime.getVMInstallTypes();		for (int i= 0; i < vmTypes.length; i++) {			IVMInstall[] vms= vmTypes[i].getVMInstalls();			if (vms.length > 0)				return vms[0];		}		return null;	}		public void setVisible(boolean visible) {		super.setVisible(visible);		if (visible)			setTitle("Installed Java Runtime Environments");	}}