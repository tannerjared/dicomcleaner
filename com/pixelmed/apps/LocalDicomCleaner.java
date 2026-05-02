/* Copyright (c) 2001-2018, David A. Clunie DBA Pixelmed Publishing. All rights reserved. */

package com.pixelmed.apps;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Locale;
import java.util.Iterator;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import com.pixelmed.slf4j.Logger;
import com.pixelmed.slf4j.LoggerFactory;

/**
 * <p>A local-only entry point for cleaning DICOM files.</p>
 */
public class LocalDicomCleaner extends JFrame {
	private static final String identString = "@(#) LocalDicomCleaner.java";

	private static final Logger slf4jlogger = LoggerFactory.getLogger(LocalDicomCleaner.class);

	private JTextField inputPathTextField;
	private JTextField outputPathTextField;
	private JTextArea logTextArea;
	private JButton cleanButton;

	private static class CleanResult {
		private final File outputPath;
		private final Set<String> failedSet;

		private CleanResult(File outputPath,Set<String> failedSet) {
			this.outputPath = outputPath;
			this.failedSet = failedSet;
		}
	}

	public LocalDicomCleaner() {
		super("DicomCleaner");
		buildUserInterface();
	}

	private void buildUserInterface() {
		inputPathTextField = new JTextField();
		outputPathTextField = new JTextField();
		logTextArea = new JTextArea();
		logTextArea.setEditable(false);
		logTextArea.setLineWrap(true);
		logTextArea.setWrapStyleWord(true);

		JPanel formPanel = new JPanel(new GridBagLayout());
		addPathRow(formPanel,0,"Input",inputPathTextField,new JButton("Choose"),new Runnable() {
			public void run() {
				chooseInputPath();
			}
		});
		addPathRow(formPanel,1,"Output",outputPathTextField,new JButton("Choose"),new Runnable() {
			public void run() {
				chooseOutputPath();
			}
		});

		cleanButton = new JButton("Clean");
		cleanButton.addActionListener(event -> cleanSelectedPaths());

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(cleanButton);

		JPanel mainPanel = new JPanel(new BorderLayout(8,8));
		mainPanel.add(formPanel,BorderLayout.NORTH);
		mainPanel.add(new JScrollPane(logTextArea),BorderLayout.CENTER);
		mainPanel.add(buttonPanel,BorderLayout.SOUTH);
		mainPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(12,12,12,12));

		setContentPane(mainPanel);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setPreferredSize(new Dimension(720,360));
		pack();
		setLocationRelativeTo(null);
		setVisible(true);
	}

	private void addPathRow(JPanel panel,int row,String label,JTextField textField,JButton button,final Runnable action) {
		button.addActionListener(event -> action.run());

		GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.insets = new Insets(4,4,4,4);
		labelConstraints.anchor = GridBagConstraints.WEST;
		panel.add(new JLabel(label),labelConstraints);

		GridBagConstraints fieldConstraints = new GridBagConstraints();
		fieldConstraints.gridx = 1;
		fieldConstraints.gridy = row;
		fieldConstraints.weightx = 1;
		fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
		fieldConstraints.insets = new Insets(4,4,4,4);
		panel.add(textField,fieldConstraints);

		GridBagConstraints buttonConstraints = new GridBagConstraints();
		buttonConstraints.gridx = 2;
		buttonConstraints.gridy = row;
		buttonConstraints.insets = new Insets(4,4,4,4);
		panel.add(button,buttonConstraints);
	}

	private void chooseInputPath() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setDialogTitle("Choose DICOM file or folder");
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			inputPathTextField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void chooseOutputPath() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setDialogTitle("Choose output file or folder");
		if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			outputPathTextField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void cleanSelectedPaths() {
		final String inputPath = inputPathTextField.getText().trim();
		final String outputPath = outputPathTextField.getText().trim();
		if (inputPath.length() == 0 || outputPath.length() == 0) {
			JOptionPane.showMessageDialog(this,"Choose an input and output first.","Missing path",JOptionPane.WARNING_MESSAGE);
			return;
		}

		cleanButton.setEnabled(false);
		logTextArea.setText("Cleaning...\n");

		new SwingWorker<CleanResult,String>() {
			protected CleanResult doInBackground() throws Exception {
				return clean(inputPath,outputPath);
			}

			protected void done() {
				try {
					CleanResult result = get();
					if (result.failedSet == null || result.failedSet.isEmpty()) {
						logTextArea.append("Done. Output created at:\n"+result.outputPath.getCanonicalPath()+"\n");
					}
					else {
						logTextArea.append("Done with "+result.failedSet.size()+" failed file(s).\n");
						for (Iterator<String> i = result.failedSet.iterator(); i.hasNext();) {
							logTextArea.append(i.next()+"\n");
						}
					}
				}
				catch (Exception e) {
					logTextArea.append("Failed: "+e.getMessage()+"\n");
					slf4jlogger.error("Local cleaning failed",e);
				}
				finally {
					cleanButton.setEnabled(true);
				}
			}
		}.execute();
	}

	private static CleanResult clean(String inputPathName,String outputPathName) throws Exception {
		File inputPath = new File(inputPathName).getCanonicalFile();
		if (!inputPath.exists()) {
			throw new IllegalArgumentException("Input does not exist: "+inputPath);
		}

		File requestedOutputPath = new File(outputPathName).getCanonicalFile();
		boolean outputIsSingleFile = inputPath.isFile() && (requestedOutputPath.isFile() || outputPathName.toLowerCase(Locale.US).endsWith(".dcm"));
		File outputFolder = outputIsSingleFile ? requestedOutputPath.getParentFile() : requestedOutputPath;
		if (outputFolder == null) {
			outputFolder = new File(".").getCanonicalFile();
		}
		if (outputFolder.exists() && !outputFolder.isDirectory()) {
			throw new IllegalArgumentException("Output parent is not a folder: "+outputFolder);
		}
		if (!outputFolder.exists() && !outputFolder.mkdirs()) {
			throw new IllegalArgumentException("Could not create output folder: "+outputFolder);
		}

		String previousReportDisabled = System.getProperty("dicomcleaner.report.disabled");
		System.setProperty("dicomcleaner.report.disabled","true");
		DeidentifyAndRedactWithOriginalFileName cleaner;
		try {
			cleaner = new DeidentifyAndRedactWithOriginalFileName(
				inputPath.getCanonicalPath(),
				outputFolder.getCanonicalPath(),
				""/*redactionControlFileName*/,
				false/*decompress*/,
				false/*keepAllPrivate*/,
				true/*addContributingEquipmentSequence*/);
		}
		finally {
			if (previousReportDisabled == null) {
				System.clearProperty("dicomcleaner.report.disabled");
			}
			else {
				System.setProperty("dicomcleaner.report.disabled",previousReportDisabled);
			}
		}

		Set<String> failedSet = cleaner.getFilePathNamesThatFailedToProcess();
		if (outputIsSingleFile && (failedSet == null || failedSet.isEmpty())) {
			File generatedOutput = new File(outputFolder,inputPath.getName().replaceFirst("[.]dcm$","")+"_Anon.dcm").getCanonicalFile();
			if (generatedOutput.exists() && !generatedOutput.equals(requestedOutputPath)) {
				if (requestedOutputPath.exists() && !requestedOutputPath.delete()) {
					throw new IllegalArgumentException("Could not replace output file: "+requestedOutputPath);
				}
				if (!generatedOutput.renameTo(requestedOutputPath)) {
					throw new IllegalArgumentException("Could not move cleaned output to: "+requestedOutputPath);
				}
			}
		}
		return new CleanResult(outputIsSingleFile ? requestedOutputPath : outputFolder,failedSet);
	}

	private static void printUsage() {
		System.err.println("Usage: LocalDicomCleaner inputPath outputPath");
		System.err.println("  inputPath may be a DICOM file or folder.");
		System.err.println("  outputPath may be an output folder, or an output file when inputPath is one file.");
	}

	public static void main(String args[]) {
		if (args.length == 0) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					new LocalDicomCleaner();
				}
			});
		}
		else if (args.length == 2) {
			try {
				CleanResult result = clean(args[0],args[1]);
				if (result.failedSet == null || result.failedSet.isEmpty()) {
					System.err.println("Done. Output created at: "+result.outputPath.getCanonicalPath());
				}
				else {
					System.err.println("Done with "+result.failedSet.size()+" failed file(s).");
					for (Iterator<String> i = result.failedSet.iterator(); i.hasNext();) {
						System.err.println(i.next());
					}
					System.exit(2);
				}
			}
			catch (Exception e) {
				slf4jlogger.error("Local cleaning failed",e);
				System.exit(1);
			}
		}
		else {
			printUsage();
			System.exit(1);
		}
	}
}
