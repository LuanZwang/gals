# GALS — fonte original, corrigido e com IA

GALS (Gerador de Analisadores Léxicos e Sintáticos), de Carlos Eduardo Gesser, a partir do **código-fonte
original** (`gals-src.zip`, versão de 03/10/2003, a mesma do `gals.jar` distribuído), com correções e
integração com IA.

O histórico começa pelo fonte original sem nenhuma alteração. Depois vêm a conversão para UTF-8 e cada
mudança, em commits separados. A branch `base-descompilada` guarda a primeira versão deste trabalho,
feita a partir do `gals.jar` descompilado, antes de o fonte original ser encontrado.

## Compilar e executar

```
mvn package
java -jar target/gals.jar            # interface gráfica
java -jar target/gals.jar --help     # linha de comando
```

O jar tem ~50 MB porque embute o SDK oficial da Anthropic, usado pelo assistente.

## Linha de comando (sem janela)

```
java -jar target/gals.jar check linguagem.gals
java -jar target/gals.jar info linguagem.gals
java -jar target/gals.jar first-follow linguagem.gals
java -jar target/gals.jar simulate linguagem.gals programa.txt
java -jar target/gals.jar generate linguagem.gals src/main/java/pacote --Package=pacote --Input=Stream --skip=Semantico.java
```

`check` também verifica se a gramática é LL(1) (recursão à esquerda, fatoração, conflitos) ou se há conflitos LR.
`--Opcao=Valor` sobrescreve uma opção do `.gals` e `--skip` preserva arquivos editados à mão.

## Servidor MCP (Claude Code)

```
claude mcp add gals -- java -jar "<caminho>/gals-src/target/gals.jar" mcp
```

Ferramentas: `gals_reference`, `gals_check`, `gals_info`, `gals_first_follow`, `gals_simulate`, `gals_generate`.

## Assistente dentro do GALS

Use **Ferramentas → Assistente IA** (Ctrl+I). O painel conversa com o Claude (`claude-opus-5-5`), que lê, verifica,
simula e edita a especificação aberta. Cada edição dele vira um passo de Ctrl+Z. É preciso ter uma chave da API
Anthropic, que é paga por uso: defina `ANTHROPIC_API_KEY` ou informe a chave na primeira vez.

## O que mudou em relação ao original

- **Compilação no JDK atual**: as tabelas `SCANNER_TABLE` e `SYNT_TABLE` de `gas/Constants` viraram literais em
  string, porque passavam do limite de 64KB por método do javac atual (os valores são idênticos). Os ícones
  foram extraídos do `gals.jar`, e o projeto passou a usar Maven.
- **Desfazer/Refazer**: histórico próprio para cada editor, agrupado por palavra; Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z.
  Uma exceção no realce de sintaxe não desliga mais o Ctrl+Z, os atalhos não vazam para outros campos, salvar
  não apaga o histórico e foram adicionados um menu Editar e botões na barra.
- **Atalhos**: Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S e Ctrl+I.
- **Importar BNF**: pergunta antes de descartar alterações e não sobrescreve o `.gals` que estava aberto.
- **`Input = String`**: o original carregava essa opção como `Stream` (`Options.setOption`); agora é respeitada.
- **Núcleo sem janela**: as opções e os conflitos LL/LR não dependem mais de diálogos Swing (`GalsEngine`).

Sem as opções novas, a geração de código é idêntica à do `gals.jar` original em 495 arquivos
(Java/C++/Delphi × 5 parsers × 3 tabelas).

## Licença e créditos

GALS foi desenvolvido por **Carlos Eduardo Gesser** como trabalho de conclusão de curso na UFSC, com orientação do
Prof. Olinto José Varela Furtado. O código-fonte é distribuído sob a **Licença Pública Geral GNU (GPL)**
(<https://gals.sourceforge.net/help.html>); a página não indica a versão.

Este repositório é uma versão modificada, distribuída sob a mesma licença. As modificações estão descritas acima
e no histórico de commits.
