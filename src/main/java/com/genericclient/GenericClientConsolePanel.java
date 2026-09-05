package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

final class GenericClientConsolePanel extends JPanel
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String RUN_ACTION = "genericclient.console.run";

	private final JLabel lastResult = GenericClientDashboardStyle.mono("None yet");
	private final JTextArea input = GenericClientDashboardStyle.textArea(
		"return ScriptScope.current().read(\"player\", Map.of());", true, false);
	private final JTextArea output = GenericClientDashboardStyle.textArea(
		"Results print here as JSON.", false, true);

	GenericClientConsolePanel(GenericClientDashboardActions actions, GenericClientScriptHost host)
	{
		setLayout(new BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);

		input.setText("return ScriptScope.current().read(\"player\", Map.of());");
		JButton run = GenericClientDashboardStyle.primaryButton("Run");
		run.addActionListener(event ->
		{
			output.setText("Running...");
			host.evaluate(input.getText()).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
			{
				output.setText(error == null ? GSON.toJson(result) : GenericClientDashboardStyle.message(error));
				output.setCaretPosition(0);
			}));
		});
		input.getInputMap(JComponent.WHEN_FOCUSED).put(
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), RUN_ACTION);
		input.getActionMap().put(RUN_ACTION, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent event)
			{
				run.doClick();
			}
		});
		GenericClientDashboardStyle.Card java = GenericClientDashboardStyle.card("Java");
		java.put(GenericClientDashboardStyle.inset(input, 84))
			.gap(10)
			.put(GenericClientDashboardStyle.inline(10, run, GenericClientDashboardStyle.small("Ctrl+Enter runs the snippet")))
			.gap(10)
			.put(GenericClientDashboardStyle.inset(output, 132));

		JButton status = GenericClientDashboardStyle.button("Status");
		status.addActionListener(event -> actions.printDiagnostics());
		JButton walk = GenericClientDashboardStyle.button("Walk test");
		walk.addActionListener(event -> actions.walkToRandomTile());
		JPanel result = GenericClientDashboardStyle.panel(new BorderLayout(10, 0));
		result.add(GenericClientDashboardStyle.small("Last result"), BorderLayout.WEST);
		result.add(lastResult, BorderLayout.CENTER);

		GenericClientDashboardStyle.Card diagnostics = GenericClientDashboardStyle.card("Diagnostics");
		diagnostics.put(GenericClientDashboardStyle.inline(8, status, walk))
			.gap(10)
			.put(result);

		JPanel page = GenericClientDashboardStyle.page();
		page.add(GenericClientDashboardStyle.stack(16,
			GenericClientDashboardStyle.pageHeader("Console"),
			java,
			diagnostics), BorderLayout.NORTH);
		add(GenericClientDashboardStyle.scroll(page), BorderLayout.CENTER);
	}

	void updateLastResult(String result)
	{
		String text = result == null ? "" : result.trim();
		lastResult.setText(text.isEmpty() ? "None yet" : text);
		lastResult.setToolTipText(text.isEmpty() ? null : text);
	}
}
