package gesser.gals;

import gesser.gals.generator.Options;
import gesser.gals.generator.OptionsDialog;
import gesser.gals.generator.parser.Grammar;
import gesser.gals.generator.scanner.FiniteAutomata;
import gesser.gals.util.GalsData;
import gesser.gals.util.MetaException;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.*;

public class MainWindow extends JFrame 
{
	private static final ImageIcon GALS = new ImageIcon(ClassLoader.getSystemResource("icons/gals.gif"));
	
	private static MainWindow singleton = null;
	
	public static MainWindow getInstance()
	{
		if (singleton == null)
		{
			singleton = new MainWindow();			
		}
		return singleton;
	}
		
	JToolBar toolbar = new JToolBar();
		
	private JMenuBar menuBar = new JMenuBar();
	private JMenu grammar = new JMenu("Verificar");
	private JMenuItem lexTable = new JMenuItem(Actions.viewLexTable);
    private JMenuItem syntTable = new JMenuItem(Actions.showTable);
    private JMenuItem ff = new JMenuItem(Actions.ff);
	private JMenuItem useless = new JMenuItem(Actions.useless);
    private JMenuItem itemSet = new JMenuItem(Actions.showItemSet);
	
	private InputPane inPane = new InputPane();
	
	private boolean needRebuildFA = true;
	private boolean needRebuildGram = true;	
	private FiniteAutomata fa = null;
	private Grammar gram = null;
	
	private gesser.gals.ai.AssistantPanel assistantPanel;
	private JSplitPane assistantSplit;
	private final Action toggleAssistant = new AbstractAction("Assistente IA")
	{
		{
			putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_MASK));
			putValue(SHORT_DESCRIPTION, "Mostrar/ocultar o assistente de IA (Ctrl+I)");
		}
		
		public void actionPerformed(ActionEvent e)
		{
			toggleAssistant();
		}
	};
	
	/** Mostra ou esconde o painel do assistente de IA à direita dos editores. */
	public void toggleAssistant()
	{
		Container cp = getContentPane();
		if (assistantSplit != null && assistantSplit.getParent() == cp)
		{
			cp.remove(assistantSplit);
			cp.add(inPane);
		}
		else
		{
			if (assistantPanel == null)
				assistantPanel = new gesser.gals.ai.AssistantPanel();
			cp.remove(inPane);
			assistantSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inPane, assistantPanel);
			assistantSplit.setResizeWeight(0.65);
			cp.add(assistantSplit);
			if (getWidth() < 1100)
			{
				setSize(Math.min(1200, Toolkit.getDefaultToolkit().getScreenSize().width - 40), Math.max(getHeight(), 650));
				gesser.gals.GALS.centralize(this);
			}
		}
		cp.validate();
		cp.repaint();
		inPane.fitErrorList();
	}
	
	public InputPane getInputPane()
	{
		return inPane;
	}
	
	/** InputPane já criado, ou null durante a construção da janela. */
	static InputPane peekInputPane()
	{
		return singleton == null ? null : singleton.inPane;
	}
	
	public void setChanged()
	{
		needRebuildFA = true;
		needRebuildGram = true;
	}
	
	private MainWindow()
	{
		super ("GALS - Gerador de Analisadores Léxicos e Sintáticos");
		
		setIconImage(GALS.getImage());
		
		createMenu();
		createToolBar();
	
		getContentPane().add(toolbar, BorderLayout.NORTH);
		getContentPane().add(inPane);
     
     
        
        pack();
        setSize(600,500);
        
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter(){
        	public void windowClosing(WindowEvent e)
			{
				Actions.close.actionPerformed(null);
			}
        });
        
		grammar.setVisible(false);
	}
	
	public Grammar getGrammar() throws MetaException
    {
    	if (needRebuildGram || gram == null)
    	{
    		gram = inPane.getGrammar();
    		needRebuildGram = false;
    	}
    	return gram;
    }
    
    public FiniteAutomata getFiniteAutomata() throws MetaException 
    {    	
    	if (needRebuildFA || fa == null)
    	{
    		fa = inPane.getFiniteAutomata();
    		needRebuildFA = false;
    	}
    	return fa;
    }
        
    public Options getOptions()
    {
    	return OptionsDialog.getInstance().getOptions();
    }
    
	public void updateData(GalsData lsd)
	{
		reset();
		
		if (lsd.getOptions() != null)
		{
			OptionsDialog.getInstance().setOptions(lsd.getOptions());
			setPanesMode(OptionsDialog.getInstance().getMode());
		}
			
		inPane.setData(lsd.getData());
		// arquivo recém-aberto: Ctrl+Z não deve "desabrir" o conteúdo
		inPane.clearHistory();
	}
	
	public InputPane.Data getData()
	{
		return inPane.getData();
	}
	
	public void setData(InputPane.Data data)
	{
		inPane.setData(data);
	}

	public void reset()
	{
		inPane.reset();	
		setPanesMode(OptionsDialog.getInstance().getMode());	
	}
	
	public List getTokens() throws MetaException
	{
		List tokens = inPane.getTokens();
		
		while ( tokens.remove("\n") )
			;
			
		return tokens;
	}
	
	public void handleError(MetaException e)
	{
		inPane.handleError(e);
	}
	
	private void createMenu()
    {	
    	JMenu file = new JMenu("Arquivo");
    	
    	file.add(new JMenuItem(Actions.new_));
    	file.add(new JMenuItem(Actions.load));
    	file.addSeparator();
    	file.add(new JMenuItem(Actions.save));
    	file.add(new JMenuItem(Actions.saveAs));
    	file.addSeparator();		
		file.add(new JMenuItem(Actions.importGAS));
		file.addSeparator();
    	file.add(new JMenuItem(Actions.close));
    	
    	JMenu edit = new JMenu("Editar");
    	
    	edit.add(new JMenuItem(Actions.undo));
    	edit.add(new JMenuItem(Actions.redo));
    	
    	JMenu tools = new JMenu("Ferramentas");

    	tools.add(new JMenuItem(Actions.verify));
		tools.add(new JMenuItem(Actions.genCode));
		tools.addSeparator();
		tools.add(new JMenuItem(Actions.simulate));
    	tools.add(grammar);
		tools.add(useless);
		tools.addSeparator();
		tools.add(new JMenuItem(Actions.options));
		tools.addSeparator();
		tools.add(new JMenuItem(toggleAssistant));
    	
    	grammar.add(new JMenuItem(Actions.factored));
    	grammar.add(new JMenuItem(Actions.recursion));
    	grammar.add(new JMenuItem(Actions.condition3));
    	
    	JMenu doc = new JMenu("Documentação");
    	
    	doc.add(lexTable);
    	doc.add(syntTable); //Sintatico - LL(1)
		doc.add(itemSet);     //Sintatico - LR(1)
    	doc.add(ff);     //Sintatico    	
    	
    	/*
    	JMenu transform = new JMenu("Transformações");
    	
    	transform.add(new JMenuItem(Actions.undo));
    	Actions.undo.setEnabled(false);
    	transform.addSeparator();
    	transform.add(Actions.factorate);
    	transform.add(Actions.removeRecursion);
    	transform.add(Actions.removeUnitary);
    	transform.add(Actions.removeUseless);
    	transform.add(Actions.removeEpsilon);*/

		JMenu help = new JMenu("Ajuda");
    	
		help.add(Actions.doc);
    	help.add(Actions.about);

		menuBar.add(file);
		menuBar.add(edit);
		//menuBar.add(transform);
		menuBar.add(tools);
		menuBar.add(doc);
		menuBar.add(help);

        setJMenuBar(menuBar);
    }
    
	private void createToolBar()
	{
		toolbar.setFloatable(false);
		
		toolbar.add(Actions.new_);
		toolbar.add(Actions.load);
		toolbar.add(Actions.save);
		
		toolbar.addSeparator();
		
		toolbar.add(Actions.undo).setFocusable(false);
		toolbar.add(Actions.redo).setFocusable(false);
		
		toolbar.addSeparator();
		
		toolbar.add(Actions.verify);
		
		toolbar.addSeparator();
		
		toolbar.add(Actions.simulate);
		toolbar.add(Actions.genCode);
		
		toolbar.addSeparator();
		
		toolbar.add(Actions.options);
		
		toolbar.addSeparator();
		
		toolbar.add(toggleAssistant).setFocusable(false);
	}
    
	public void setPanesMode(int mode)
	{
		inPane.setMode(mode);
		
		boolean lex  = mode == InputPane.LEXICAL || mode == InputPane.BOTH;
		boolean synt = mode == InputPane.SYNTATIC || mode == InputPane.BOTH;
		int type = OptionsDialog.getInstance().getOptions().parser;
		boolean ll = type == Options.PARSER_LL || type == Options.PARSER_REC_DESC;
		
		lexTable.setVisible(lex);
		
		syntTable.setVisible(synt);
		ff.setVisible(synt);
		grammar.setVisible(synt && ll);
		
		itemSet.setVisible( synt && ! ll);
	}
}

