package com.opensymphony.module.sitemesh.parser.tokenizer;

import com.opensymphony.module.sitemesh.util.CharArray;
import com.opensymphony.module.sitemesh.util.CharArrayReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks for patterns of tokens in the Lexer and translates these to calls to pass to the TokenHandler.
 *
 * @author Joe Walnes
 * @see TagTokenizer
 */
class Parser extends Lexer implements Text, Tag {

    private final CharArray attributeBuffer = new CharArray(64);
    private int pushbackToken = -1;
    private String pushbackText;

    public final static short SLASH=257;
    public final static short WHITESPACE=258;
    public final static short EQUALS=259;
    public final static short QUOTE=260;
    public final static short WORD=261;
    public final static short TEXT=262;
    public final static short QUOTED=263;
    public final static short LT=264;
    public final static short GT=265;

    private final char[] input;

    private TokenHandler handler;

    private int position;
    private int length;

    private String name;
    private int type;
    private final List attributes = new ArrayList();

    public Parser(char[] input, TokenHandler handler) {
        super(new CharArrayReader(input));
        this.input = input;
        this.handler = handler;
    }

    private String text() {
        if (pushbackToken == -1) {
            return yytext();
        } else {
            return pushbackText;
        }
    }

    private void skipWhiteSpace() throws IOException {
        while (true) {
            int next = takeNextToken();
            if (next != Parser.WHITESPACE) {
                pushBack(next);
                break;
            }
        }
    }

    private void pushBack(int next) {
        if (pushbackToken != -1) {
            fatal("Cannot pushback more than once");
        }
        pushbackToken = next;
        if (next == Parser.WORD || next == Parser.QUOTED || next == Parser.SLASH || next == Parser.EQUALS) {
            pushbackText = yytext();
        } else {
            pushbackText = null;
        }
    }

    private int takeNextToken() throws IOException {
        if (pushbackToken == -1) {
            int result = yylex();
            if (result == 0) {
                throw new IOException();
            } else {
                return result;
            }
        } else {
            int result = pushbackToken;
            pushbackToken = -1;
            pushbackText = null;
            return result;
        }
    }

    public void start() {
        try {
            while (true) {
                int token = takeNextToken();
                if (token == 0) {
                    // EOF
                    return;
                } else if (token == Parser.TEXT) {
                    // Got some text
                    parsedText(position(), length());
                } else if (token == Parser.LT) {
                    // Token "<" - start of tag
                    parseTag();
                } else {
                    fatal("Unexpected token from lexer, was expecting TEXT or LT");
                }
            }
        } catch (IOException e) {

        }
    }

    private void parseTag() throws IOException {
        // Start parsing a TAG

        int start = position();
        skipWhiteSpace();
        int token = takeNextToken();
        int type = Tag.OPEN;
        String name;

        if (token == Parser.SLASH) {
            // Token "/" - it's a closing tag
            type = Tag.CLOSE;
            token = takeNextToken();
        }

        if (token == Parser.WORD) {
            // Token WORD - name of tag
            name = text();

            if (handler.caresAboutTag(name)) {
                parseFullTag(type, name, start);
            } else {

                // don't care about this tag... scan to the end and treat it as text
                while(true)  {
                    token = takeNextToken();
                    if (token == Parser.GT) {
                        parsedText(start, position() - start + 1);
                        break;
                    }
                }
            }

        } else if (token == Parser.GT) {
            // Token ">" - an illegal <> or <  > tag. Ignore
        } else {
            fatal("Could not recognise tag"); // TODO: this should be recoverable
        }
    }

    private void parseFullTag(int type, String name, int start) throws IOException {
        int token;
        while (true) {
            skipWhiteSpace();
            token = takeNextToken();
            pushBack(token);

            if (token == Parser.SLASH || token == Parser.GT) {
                break; // no more attributes here
            } else if (token == Parser.WORD) {
                parseAttribute(); // start of an attribute
            } else {
                fatal("XXY");
            }
        }

        token = takeNextToken();
        if (token == Parser.SLASH) {
            // Token "/" - it's an empty tag
            type = Tag.EMPTY;
            token = takeNextToken();
        }

        if (token == Parser.GT) {
            // Token ">" - YAY! end of tag.. process it!
            parsedTag(type, name, start, position() - start + 1);
        } else {
            fatal("Expected end of tag");
        }
    }

    private void parseAttribute() throws IOException {
        int token = takeNextToken();
        // Token WORD - start of an attribute
        String attributeName = text();
        skipWhiteSpace();
        token = takeNextToken();
        if (token == Parser.EQUALS) {
            // Token "=" - the attribute has a value
            skipWhiteSpace();
            token = takeNextToken();
            if (token == Parser.QUOTED) {
                // token QUOTED - a quoted literal as the attribute value
                parsedAttribute(attributeName, text(), true);
            } else if (token == Parser.WORD || token == Parser.SLASH) {
                // unquoted word
                attributeBuffer.clear();
                attributeBuffer.append(text());
                while (true) {
                    int next = takeNextToken();
                    if (next == Parser.WORD || next == Parser.EQUALS || next == Parser.SLASH) {
                        attributeBuffer.append(text());
                        // TODO: how to handle <a x=c/> ?
                    } else {
                        pushBack(next);
                        break;
                    }
                }
                parsedAttribute(attributeName, attributeBuffer.toString(), false);
            } else if (token == Parser.SLASH || token == Parser.GT) {
                // no more attributes
                pushBack(token);
            } else {
                fatal("Illegal attribute value"); // TODO: recover
            }
        } else if (token == Parser.SLASH || token == Parser.GT || token == Parser.WORD) {
            // it was a value-less HTML style attribute
            parsedAttribute(attributeName, null, false);
            pushBack(token);
        } else {
            fatal("Illegal attribute name"); // TODO: recover
        }
    }

    public void parsedText(int position, int length) {
        this.position = position;
        this.length = length;
        handler.text((Text) this);
    }

    public void parsedTag(int type, String name, int start, int length) {
        this.type = type;
        this.name = name;
        this.position = start;
        this.length = length;
        handler.tag((Tag) this);
        attributes.clear();
    }

    public void parsedAttribute(String name, String value, boolean quoted) {
        attributes.add(name);
        if (quoted) {
            attributes.add(value.substring(1, value.length() - 1));
        } else {
            attributes.add(value);
        }
    }

    public void error(String message, int line, int column) {
        handler.error(message, line, column);
    }

    protected void reportError(String message, int line, int column) {
//        System.out.println(message);
        error(message, line, column);
    }

    private void fatal(String message) {
        error(message, line(), column());
        throw new RuntimeException(message);
    }

    public String getName() {
        return name;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return new String(input, position, length);
    }

    public void writeTo(CharArray out) {
        out.append(input, position, length);
    }

    public int getAttributeCount() {
        return attributes == null ? 0 : attributes.size() / 2;
    }

    public String getAttributeName(int index) {
        return (String) attributes.get(index * 2);
    }

    public String getAttributeValue(int index) {
        return (String) attributes.get(index * 2 + 1);
    }

    public String getAttributeValue(String name) {
        // todo: optimize
        if (attributes == null) {
            return null;
        }
        final int len = attributes.size();
        for (int i = 0; i < len; i+=2) {
            if (name.equalsIgnoreCase((String) attributes.get(i))) {
                return (String) attributes.get(i + 1);
            }
        }
        return null;
    }

    public boolean hasAttribute(String name) {
        return getAttributeValue(name) != null;
    }

}