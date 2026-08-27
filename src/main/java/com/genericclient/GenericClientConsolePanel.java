package com.genericclient;

import com.google.gson.GsonBuilder;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

final class GenericClientConsolePanel extends JPanel
{
	private final GenericClientDashboardActions actions;
	private final GenericClientLuaHost host;
	private final JLabel lastResult = GenericClientDashboardStyle.value("Ready");
	private final JTextArea input = GenericClientDashboardStyle.textArea("return gc.read(\"player\")", 5, false);
	private final JTextArea output = GenericClientDashboardStyle.textArea("No result yet.", 8, true);
	private final JTextArea npcs = GenericClientDashboardStyle.textArea("No NPC snapshot yet.", 13, true);

	GenericClientConsolePanel(GenericClientDashboardActions actions, GenericClientLuaHost host)
	{
		this.actions = actions;
		this.host = host;
		setLayout(new java.awt.BorderLayout());
		setBackground(GenericClientDashboardStyle.BACKGROUND);
		JPanel page = GenericClientDashboardStyle.page();

		JPanel tools = GenericClientDashboardStyle.section("Tools");
		JPanel toolButtons = new JPanel(new GridLayout(1, 3, 4, 4));
		toolButtons.setOpaque(false);
		JButton diagnostics = GenericClientDashboardStyle.button("Status");
		diagnostics.addActionListener(event -> actions.printDiagnostics());
		toolButtons.add(diagnostics);
		JButton npcButton = GenericClientDashboardStyle.button("NPCs");
		npcButton.addActionListener(event -> actions.logNearbyNpcs());
		toolButtons.add(npcButton);
		JButton walk = GenericClientDashboardStyle.button("Walk test");
		walk.addActionListener(event -> actions.walkToRandomTile());
		toolButtons.add(walk);
		tools.add(toolButtons);
		tools.add(GenericClientDashboardStyle.row("Last", lastResult));
		page.add(tools);
		page.add(Box.createVerticalStrut(14));

		JPanel repl = GenericClientDashboardStyle.section("Lua console");
		repl.add(GenericClientDashboardStyle.scroll(input, 105));
		JPanel replButtons = new JPanel(new GridLayout(1, 2, 4, 4));
		replButtons.setOpaque(false);
		JButton run = GenericClientDashboardStyle.primaryButton("Run");
		run.addActionListener(event ->
		{
			output.setText("Running...");
			host.evaluate(input.getText()).whenComplete((result, error) ->
				javax.swing.SwingUtilities.invokeLater(() -> output.setText(error == null
					? new GsonBuilder().setPrettyPrinting().create().toJson(result)
					: error.getMessage())));
		});
		replButtons.add(run);
		JButton reset = GenericClientDashboardStyle.button("Reset");
		reset.addActionListener(event -> host.resetRepl().whenComplete((result, error) ->
			javax.swing.SwingUtilities.invokeLater(() -> output.setText(
				error == null ? result : error.getMessage()))));
		replButtons.add(reset);
		repl.add(replButtons);
		output.setEditable(false);
		repl.add(GenericClientDashboardStyle.scroll(output, 160));
		page.add(repl);
		page.add(Box.createVerticalStrut(14));

		JPanel npcSection = GenericClientDashboardStyle.section("Nearby NPCs");
		npcs.setEditable(false);
		npcSection.add(GenericClientDashboardStyle.scroll(npcs, 260));
		page.add(npcSection);
		add(GenericClientDashboardStyle.pageScroll(page), java.awt.BorderLayout.CENTER);
	}

	void updateLastResult(String result)
	{
		lastResult.setText("<html>" + escape(result) + "</html>");
	}

	void updateNpcDiagnostics(String diagnostics)
	{
		npcs.setText(diagnostics);
		npcs.setCaretPosition(0);
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
