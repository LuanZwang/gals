package gesser.gals;

import gesser.gals.analyser.AnalysisError;
import gesser.gals.gas.BNFImporter;
import gesser.gals.generator.Options;
import gesser.gals.generator.OptionsDialog;
import gesser.gals.generator.cpp.*;
import gesser.gals.generator.java.*;
import gesser.gals.generator.parser.*;
import gesser.gals.generator.parser.ll.*;
import gesser.gals.generator.parser.lr.*;
import gesser.gals.generator.delphi.*;
import gesser.gals.generator.scanner.FiniteAutomata;
import gesser.gals.simulator.SimulateWindow;
import gesser.gals.util.*;

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.URL;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;

public class Actions
{
	private static abstract class ToolTipedAction extends AbstractAction
	{
		public ToolTipedAction(String name, Icon icon, String tooltip)
		{
			super(name, icon);
			putValue(SHORT_DESCRIPTION, tooltip);
		}
		
		public ToolTipedAction(String name, Icon icon)
		{
			this(name, icon, name);
		}
	}
	
	private static final Icon NEW = new ImageIcon(ClassLoader.getSystemResource("icons/new.gif"));
	private static final Icon OPEN = new ImageIcon(ClassLoader.getSystemResource("icons/open.gif"));
	private static final Icon SAVE = new ImageIcon(ClassLoader.getSystemResource("icons/save.gif"));	
	private static final Icon VERIFY = new ImageIcon(ClassLoader.getSystemResource("icons/verify.gif"));
	private static final Icon SIMULATOR = new ImageIcon(ClassLoader.getSystemResource("icons/simulator.gif"));
	private static final Icon GENERATOR = new ImageIcon(ClassLoader.getSystemResource("icons/generator.gif"));
	private static final Icon OPTIONS = new ImageIcon(ClassLoader.getSystemResource("icons/options.gif"));
	private static final Icon UNDO = new ImageIcon(ClassLoader.getSystemResource("icons/undo.gif"));
	private static final Icon REDO = new ImageIcon(ClassLoader.getSystemResource("icons/redo.gif"));
	
	public static final JFileChooser FILE_CHOOSER = new JFileChooser();
	
	private static Grammar OLD_GRAMMAR = null;
	
	private static boolean saved = false;
	private static boolean changed = false;
	private static File file = null;
	
	public static void setSaved(boolean s)
	{
		// A versão original descartava todo o histórico de desfazer ao salvar.
		saved = s;
		changed = !s;		
	}
	
	public static boolean checkSaved()
	{
		if (! saved && changed)
		{
			switch (JOptionPane.showConfirmDialog(MainWindow.getInstance(),"Salvar Alterações?"))
			{
				case JOptionPane.YES_OPTION:
					save.actionPerformed(null);
					break;
				case JOptionPane.NO_OPTION:
					return true;
				case JOptionPane.CANCEL_OPTION:
					return false;	
				default:
					return false;				
			}
		}
		return true;
	}
	
	public static final Action close = new AbstractAction("Fechar") 
	{
		public void actionPerformed(ActionEvent e) 
		{
			if (checkSaved())
        		System.exit(0);
		}		
	};
	
	public static final Action about = new AbstractAction("Sobre")
	{
		public void actionPerformed(ActionEvent e) 
		{
			String msg = 
				"G.A.L.S.\n"+
				"Gerador de Analisadores\n"+
				"Léxicos e Sintáticos (Versão 2003.10.03)\n"+
				"\n"+
				"Carlos Eduardo Gesser\n"+
				"gals.sourceforge.net";
        	JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
		}		
	};
	
	public static final Action doc = new AbstractAction("Documentação")
	{
		public void actionPerformed(ActionEvent e) 
		{
			URL url = ClassLoader.getSystemResource("help.html");
			HTMLDialog.getInstance().show("Documentação", url);
		}		
	};
	
	public static final Action options = new ToolTipedAction("Opções", OPTIONS)
	{
		
		public void actionPerformed(ActionEvent e) 
		{
			OptionsDialog.getInstance().show();
		}		
	};
	
	public static final Action save = new ToolTipedAction("Salvar", SAVE)
	{
		{
			putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_MASK));
		}
		
		public void actionPerformed(ActionEvent e) 
		{
			if (file == null/*! saved*/)
				saveAs.actionPerformed(e);
			else
			{
                try
                {
                	InputPane.Data inData = MainWindow.getInstance().getData();
                    GalsData data = new GalsData(MainWindow.getInstance().getOptions(), inData);
                    
                    OutputStream os = new FileOutputStream(file);
                    XMLProcessor.store(data, os);                    
                    setSaved(true);
                }
                catch (FileNotFoundException e1)              
                {
                	e1.printStackTrace();
                }
			}
		}		
	};
	
	public static final Action saveAs = new AbstractAction("Salvar Como...")
	{
		{
			putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_MASK | InputEvent.SHIFT_MASK));
		}
		
		public void actionPerformed(ActionEvent e) 
		{
            FILE_CHOOSER.setFileFilter(FileFilters.GALS_FILTER);
            if (FILE_CHOOSER.showSaveDialog(MainWindow.getInstance()) == JFileChooser.APPROVE_OPTION)
            {
            	file = FILE_CHOOSER.getSelectedFile();
            	String name = file.getPath();
            	if (name.length() < 5 || !name.substring(name.length()-5).equals(".gals"))
            	{
            		name = name+".gals";
            		file = new File(name);
            	}                    	
            	save.actionPerformed(e);
            }
    	}		
	};
	
	public static final Action new_ = new ToolTipedAction("Novo", NEW, "Criar Novo Arquivo")
	{
		{
			putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_MASK));
		}
		
		public void actionPerformed(ActionEvent e) 
		{
			if (checkSaved())
			{
				reset();
				options.actionPerformed(e);
			}
		}					
	};
	
	public static void reset()
	{
		MainWindow.getInstance().reset();
		saved = false;
		changed = false;
		file = null;
	}
	
	/** Habilita/desabilita Desfazer e Refazer conforme o editor ativo. */
	public static void updateUndoState()
	{
		InputPane pane = MainWindow.peekInputPane();
		if (pane != null && pane.getActiveEditor() != null)
		{
			undo.setEnabled(pane.canUndo());
			redo.setEnabled(pane.canRedo());
		}
	}
	
	public static final Action load = new ToolTipedAction("Abrir...", OPEN, "Abrir Arquivo")
	{
		{
			putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_MASK));
		}
		
		public void actionPerformed(ActionEvent e) 
		{
			if (checkSaved())
			{
            	String msg;
                try
                {
                	FILE_CHOOSER.setFileFilter(FileFilters.GALS_FILTER);
                    if (FILE_CHOOSER.showOpenDialog(MainWindow.getInstance()) == JFileChooser.APPROVE_OPTION)
                    {
                    	file = FILE_CHOOSER.getSelectedFile();
                    	InputStream is = new FileInputStream(file);
                    	GalsData lsd = XMLProcessor.load(is);
                    	MainWindow.getInstance().updateData(lsd);
                    	setSaved(true);
                    }
                }
                catch (XMLParsingException e1)
                {    
                	e1.printStackTrace();            	
                    msg = "Arquivo inválido!!!";
                    JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
                }    
                catch (IOException e1)
                {
                	e1.printStackTrace();
                }
			}
		}		
	};
	
	public static final Action showTable = new AbstractAction("Tabela de Análise Sintática")
	{
		public void actionPerformed(ActionEvent e) 
		{
        	try
            {
            	Grammar g = MainWindow.getInstance().getGrammar();

				switch (OptionsDialog.getInstance().getOptions().parser)
				{
					case Options.PARSER_REC_DESC:
					case Options.PARSER_LL:
						LLParser llg = new LLParser(g);
						HTMLDialog.getInstance().show("Tabela de Análise LL(1)", llg.tableAsHTML());
						break;
					case Options.PARSER_SLR:
					case Options.PARSER_LALR:
					case Options.PARSER_LR:
            			LRGenerator parser = LRGeneratorFactory.createGenerator(g);
         				HTMLDialog.getInstance().show("Tabela de Análise SLR(1)", parser.tableAsHTML());
            			break;
				}
			} 
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }       
			catch (NotLLException e1)
			{
				String msg = "Esta gramática não é LL(1): "+e1.getMessage();
				JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
				e1.printStackTrace();
			}       
		}		
	};
	
	public static final Action simulate = new ToolTipedAction("Simulador", SIMULATOR)
	{
		public void actionPerformed(ActionEvent e) 
		{	
            try
            {
            	FiniteAutomata fa = MainWindow.getInstance().getFiniteAutomata();
            	
            	Grammar g = MainWindow.getInstance().getGrammar();
            	
            	List terminals = MainWindow.getInstance().getTokens();
				
				switch (OptionsDialog.getInstance().getOptions().parser)
				{
					case Options.PARSER_REC_DESC:
					case Options.PARSER_LL:
						SimulateWindow.getInstance().simulateLL(fa, g,  terminals);
						break;
					case Options.PARSER_SLR:
					case Options.PARSER_LALR:
					case Options.PARSER_LR:
						SimulateWindow.getInstance().simulateSLR(fa, g,  terminals);
						break;
				}				
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }
            catch (NotLLException e1)
			{
				String msg = "Esta gramática não é LL(1): "+e1.getMessage();
				JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
				e1.printStackTrace();
			}            
		}		
	};
	
	public static final Action factored = new AbstractAction("Fatoração")
	{
		public void actionPerformed(ActionEvent e) 
		{        	
            try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                BitSet bs = g.getNonFactoratedProductions();
                if (bs.cardinality() == 0)
                    msg = "Está fatorada";
                else
                {
                    StringBuffer bfr = new StringBuffer();
                    bfr.append("As produções\n");
                    for (int i=bs.nextSetBit(0); i>=0; i=bs.nextSetBit(i+1))
                    {
                        bfr.append(i).append(": ")
                            .append(g.getProductions().getProd(i))
                            .append('\n');
                    }
                    bfr.append("Não estão fatoradas");
                    msg = bfr.toString();
                }
				JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }   
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                  
		}		
	};
	
	public static final Action recursion = new AbstractAction("Recursão à Esquerda")
	{
		public void actionPerformed(ActionEvent e) 
		{        	
            try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar(); 
                int s = g.getLeftRecursiveSimbol();
                if (s == -1)
                    msg = "NÃO possui recursão";
                else
                    msg = "Foi detectada recursão à esquerda (direta ou indireta)\n" +
                        " em produções iniciadas por \""+g.getSymbols()[s]+"\"";
                    /*                   
                if (g.hasLeftRecursion())
                    msg = "Possui recursão";
                else
                    msg = "NÃO possui recursão";
                */
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                     
		}		
	};
	
	public static final Action removeUnitary = new AbstractAction("Remover Produções Unitárias")
	{
		public void actionPerformed(ActionEvent e) 
		{        	
			try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                OLD_GRAMMAR = (Grammar) g.clone();
                g.removeUnitaryProductions();
                //MainWindow.getInstance().updateData(null, g);
                msg = "Produções Unitárias Removidas";
                
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                       
		}		
	};
	
	public static final Action condition3 = new AbstractAction("Terceira Condição LL(1)")
	{
		public void actionPerformed(ActionEvent e) 
		{
            try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                if (g.passThirdCondition())
                    msg = "Passou na 3a condição";
                else
                    msg = "NÃO passou na 3a condição";
                    
				JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }          
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }
        }		
	};
	
	public static final Action removeUseless = new AbstractAction("Remover Simbolos Inúteis")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {                	
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                OLD_GRAMMAR = (Grammar) g.clone();
                try
                {
                    g.removeUselessSymbols();                    
                    //MainWindow.getInstance().updateData(null, g);
                	msg = "Símbolos iníteis removidos";
                }
                catch (EmptyGrammarException e1)
                {
                    msg = "Gramática Vazia!!!";
                }	            
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                          
        }		
	};
	
	public static final Action removeEpsilon = new AbstractAction("Remover Epsilon")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                OLD_GRAMMAR = (Grammar) g.clone();
                g.removeEpsilon();
                //MainWindow.getInstance().updateData(null, g);
                msg = "Epsilon Removido";
                
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                        
        }		
	};
	
	public static final Action removeRecursion = new AbstractAction("Remover Recursão à Esquerda")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {
            	String msg;
                Grammar g = MainWindow.getInstance().getGrammar();
                OLD_GRAMMAR = (Grammar) g.clone();
                g.removeRecursion();
                //MainWindow.getInstance().updateData(null, g);
                msg = "Recursões a esquerda removidas";
                
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                      
        }		
	};
	
	public static final Action factorate = new AbstractAction("Fatorar")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {
            	String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                
                try
				{
					OLD_GRAMMAR = (Grammar) g.clone();
					
					g.factorate();
					//MainWindow.getInstance().updateData(null, g);
	                msg = "fatorada";
				}
				catch (LeftRecursionException e1)
	            {
	                msg = "Impossível fatorar, a gramática possui recursão a esquerda";
	            }	            
                
                JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
            }   
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                       
        }		
	};
	
	public static final Action ff = new AbstractAction("First & Follow")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {
            	//String msg;
            	
                Grammar g = MainWindow.getInstance().getGrammar();
                //msg = g.stringFirstFollow();
                
                //JOptionPane.showMessageDialog(MainWindow.getInstance(),msg);
                
				HTMLDialog.getInstance().show("First & Follow", g.ffAsHTML());
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                      
        }		
	};
	
	public static final Action showItemSet = new AbstractAction("Conjunto de Itens ")
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {
                Grammar g = MainWindow.getInstance().getGrammar();
                LRGenerator parser = LRGeneratorFactory.createGenerator(g);
                List l = parser.getErrors(parser.buildTable());
                int i=0;
                for (Iterator iter = l.iterator(); iter.hasNext(); i++)
				{
					String element = (String) iter.next();
					System.out.println(i+"->"+element);
				}
         		HTMLDialog.getInstance().show("Itens SLR(1)", parser.itemsAsHTML());
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                      
        }		
	};
	
	public static final Action viewLexTable = new AbstractAction("Tabela de Análise Léxica")
		{
			public void actionPerformed(ActionEvent e)
			{
				try
				{
					FiniteAutomata fa = MainWindow.getInstance().getFiniteAutomata();
					HTMLDialog.getInstance().show("Automato Finito", fa.asHTML());
				}
				catch(MetaException e1)
				{
					MainWindow.getInstance().handleError(e1);
				}
			}
		};
	
	public static final Action verify = new ToolTipedAction("Verificar Erros", VERIFY)
	{
		public void actionPerformed(ActionEvent e) 
		{			
            try
            {	
            	MainWindow.getInstance().getFiniteAutomata();            	
            	MainWindow.getInstance().getGrammar();
                JOptionPane.showMessageDialog(MainWindow.getInstance(), "Nenhum erro foi encontrado");
            }
            catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }                   
        }		
	};
	
	// Desfazer/Refazer atuam no editor que teve foco por último. Os atalhos
	// (Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z) são registrados em cada editor pelo InputPane.
	public static final Action undo = new ToolTipedAction("Desfazer", UNDO, "Desfazer (Ctrl+Z)")
	{
		{
			putValue(ACCELERATOR_KEY,KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_MASK));
			setEnabled(false);
		}
	
		public void actionPerformed(ActionEvent e) 
		{
			MainWindow.getInstance().getInputPane().undo();
		}	
	};
	
	public static final Action redo = new ToolTipedAction("Refazer", REDO, "Refazer (Ctrl+Y)")
	{
		{
			putValue(ACCELERATOR_KEY,KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_MASK)); 
			setEnabled(false);
		}

		public void actionPerformed(ActionEvent e) 
		{
			MainWindow.getInstance().getInputPane().redo();
		}	
	};
	
	public static final Action useless = new AbstractAction("Símbolos inúteis")
	{
		public void actionPerformed(ActionEvent e) 
		{			
			try
			{
				Grammar g = MainWindow.getInstance().getGrammar();
				HTMLDialog.getInstance().show("Símbolos inúties", g.uselessSymbolsHTML());
			}
			catch(MetaException e1)
			{
				MainWindow.getInstance().handleError(e1);
			}                              
		}
	};
	
	public static final Action genCode = new ToolTipedAction("Gerar Código", GENERATOR)
	{
		public void actionPerformed(ActionEvent e) 
		{			
			final String lb = System.getProperty("line.separator");
            try
			{				
				FiniteAutomata fa = MainWindow.getInstance().getFiniteAutomata();
				Grammar g = MainWindow.getInstance().getGrammar();				
            	
            	Options options = MainWindow.getInstance().getOptions();
				
				if (options != null)
				{            	
	            	FILE_CHOOSER.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
	            	FILE_CHOOSER.setFileFilter(FileFilters.DIRECTORY_FILTER);
	            	String title = FILE_CHOOSER.getDialogTitle();
	            	FILE_CHOOSER.setDialogTitle("Escolher Pasta");
	            							
					if (FILE_CHOOSER.showSaveDialog(MainWindow.getInstance()) == JFileChooser.APPROVE_OPTION)
					{
						String path = FILE_CHOOSER.getSelectedFile().getPath();
	            		            	
						TreeMap allFiles = new TreeMap();
						
						switch (options.language)
						{
							case Options.LANG_JAVA:
								allFiles.putAll( new JavaCommonGenerator().generate(fa, g, options) );
								allFiles.putAll( new JavaScannerGenerator().generate(fa, options) );							
								allFiles.putAll( new JavaParserGenerator().generate(g, options));
								break;
							case Options.LANG_CPP:
								allFiles.putAll( new CppCommomGenerator().generate(fa, g, options) );
								allFiles.putAll( new CppScannerGeneretor().generate(fa, options) );
								allFiles.putAll( new CppParserGenerator().generate(g, options) );
								break;
							case Options.LANG_DELPHI:
								allFiles.putAll( new DelphiCommomGenerator().generate(fa, g, options) );
								allFiles.putAll( new DelphiScannerGenerator().generate(fa, options) );
								allFiles.putAll( new DelphiParserGenerator().generate(g, options));
								break;						
						}
						
						try
						{
							if (FileGenerationSelector.getInstance().show(allFiles))
								for (Iterator i = allFiles.keySet().iterator(); i.hasNext(); )
								{
									String f = (String) i.next();
									BufferedWriter writer = new BufferedWriter(new FileWriter(path + "/" + f));
									StringBuffer bfr = new StringBuffer((String)allFiles.get(f));
									
									for (int j=0; j<bfr.length(); j++)
									{
										if (bfr.charAt(j) == '\n')
										{
											bfr.replace(j, j+1, lb);
											j += lb.length() - 1;
										}
									}
									
									writer.write( bfr.toString() );
									writer.close();									
								}
						}
						catch (IOException ee)
						{
							ee.printStackTrace();
							System.exit(1);
						}
	            	}
	            	FILE_CHOOSER.setFileSelectionMode(JFileChooser.FILES_ONLY);
	            	FILE_CHOOSER.setDialogTitle(title);
				}
			}
			catch(MetaException e1)
            {
            	MainWindow.getInstance().handleError(e1);
            }		
			catch (NotLLException e1)
			{
				JOptionPane.showMessageDialog(MainWindow.getInstance(), "Esta gramática não é LL(1): "+e1.getMessage());
				e1.printStackTrace();
			}
        }
	};
	
	public static final Action importGAS = new AbstractAction("Importar BNF")
	{
		public void actionPerformed(ActionEvent e)
		{
			// A versão original não perguntava se queria salvar e mantinha o arquivo aberto
			// como destino: "Salvar" depois de importar sobrescrevia o .gals anterior.
			if (!checkSaved())
				return;
			
			try
			{					
				GalsData lsd = new BNFImporter().importGAS();
				if (lsd != null)
				{
					MainWindow.getInstance().updateData(lsd);
					file = null;
					saved = false;
					changed = true;
				}
			}
			catch (AnalysisError ae)
			{
				JOptionPane.showMessageDialog(MainWindow.getInstance(), "Não foi possível importar o arquivo");
				ae.printStackTrace();
			}
			catch (IOException ioe)
			{
				ioe.printStackTrace();
			}
		}	
	};
}