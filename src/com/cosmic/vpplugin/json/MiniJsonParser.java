package com.cosmic.vpplugin.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimale, senza dipendenze esterne.
 *
 * Motivazione: non sappiamo a priori quali librerie (Gson, Jackson, org.json...)
 * saranno presenti nel classpath di Visual Paradigm quando il plugin verra'
 * caricato: un plugin VP viene eseguito dentro la JVM dell'IDE e non ha un
 * classpath "suo" a meno di impacchettare le dipendenze nel jar. Per evitare
 * NoClassDefFoundError in produzione, il parsing e' stato scritto a mano.
 *
 * Il risultato del parsing e' una struttura "generica":
 *  - JSON object  -> LinkedHashMap<String, Object>
 *  - JSON array   -> List<Object>
 *  - string       -> String
 *  - number       -> Double
 *  - true/false   -> Boolean
 *  - null         -> null
 *
 * La mappatura verso gli oggetti tipizzati del dominio COSMIC e' delegata a
 * {@link com.cosmic.vpplugin.model.CosmicJsonMapper}.
 */
public final class MiniJsonParser {

    private final String src;
    private int pos;

    private MiniJsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String json) {
        MiniJsonParser parser = new MiniJsonParser(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Il JSON fornito non rappresenta un oggetto radice {}");
        }
        return (Map<String, Object>) value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON troncato in posizione " + pos);
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return parseObjectInternal();
            case '[': return parseArray();
            case '"': return parseString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:  return parseNumber();
        }
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // consuma '{'
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expectChar(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; break; }
            throw new IllegalArgumentException("Atteso ',' o '}' in posizione " + pos);
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        pos++; // consuma '['
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            Object value = parseValue();
            list.add(value);
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; break; }
            throw new IllegalArgumentException("Atteso ',' o ']' in posizione " + pos);
        }
        return list;
    }

    private String parseString() {
        expectChar('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/'); break;
                    case 'n':  sb.append('\n'); break;
                    case 't':  sb.append('\t'); break;
                    case 'r':  sb.append('\r'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'u':
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default: throw new IllegalArgumentException("Escape non valido: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("Atteso '" + literal + "' in posizione " + pos);
        }
        pos += literal.length();
    }

    private void expectChar(char expected) {
        skipWhitespace();
        char c = src.charAt(pos++);
        if (c != expected) {
            throw new IllegalArgumentException("Atteso '" + expected + "' ma trovato '" + c + "' in posizione " + (pos - 1));
        }
    }

    private char peek() {
        skipWhitespace();
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
