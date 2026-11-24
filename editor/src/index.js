import { basicSetup } from "codemirror";
import { EditorView, keymap } from "@codemirror/view";
import { EditorState, Compartment } from "@codemirror/state";
import { indentWithTab, undo, redo } from "@codemirror/commands";
import { openSearchPanel } from "@codemirror/search";
import { indentUnit, StreamLanguage } from "@codemirror/language";
import { oneDark } from "@codemirror/theme-one-dark";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { xml } from "@codemirror/lang-xml";
import { java } from "@codemirror/lang-java";
import { python } from "@codemirror/lang-python";
import { cpp } from "@codemirror/lang-cpp";
import { sql } from "@codemirror/lang-sql";
import { markdown } from "@codemirror/lang-markdown";
import { php } from "@codemirror/lang-php";
import { kotlin } from "@codemirror/legacy-modes/mode/clike";

const L = {
  html: () => html(),
  css: () => css(),
  js: () => javascript(),
  json: () => json(),
  xml: () => xml(),
  java: () => java(),
  kt: () => StreamLanguage.define(kotlin),
  py: () => python(),
  cpp: () => cpp(),
  sql: () => sql(),
  md: () => markdown(),
  php: () => php(),
  txt: () => []
};

const ALIAS = {
  htm: "html", xhtml: "html", mjs: "js", jsx: "js", ts: "js", tsx: "js",
  c: "cpp", h: "cpp", hpp: "cpp", cc: "cpp", cxx: "cpp",
  kts: "kt", markdown: "md", svg: "xml", py3: "py"
};

function keyFor(name) {
  const i = name.lastIndexOf(".");
  if (i < 0) return "txt";
  let e = name.slice(i + 1).toLowerCase();
  e = ALIAS[e] || e;
  return L[e] ? e : "txt";
}

const langC = new Compartment();
const themeC = new Compartment();
let isDark = true;
let view = null;
let cb = null;

function exts(key) {
  return [
    basicSetup,
    keymap.of([indentWithTab]),
    EditorView.lineWrapping,
    indentUnit.of("    "),
    EditorState.tabSize.of(4),
    EditorView.contentAttributes.of({
      autocapitalize: "off", autocorrect: "off", spellcheck: "false"
    }),
    langC.of(L[key]()),
    themeC.of(isDark ? oneDark : []),
    EditorView.updateListener.of(u => {
      if (cb && (u.docChanged || u.selectionSet)) cb(u);
    })
  ];
}

window.CM = {
  init(parent, onUpdate) {
    cb = onUpdate;
    view = new EditorView({ parent, state: EditorState.create({ doc: "" }) });
  },
  mk(doc, name) {
    return EditorState.create({ doc, extensions: exts(keyFor(name)) });
  },
  txt(s) { return EditorState.create({ doc: s }).doc; },
  set(state) {
    view.setState(state);
    view.dispatch({ effects: themeC.reconfigure(isDark ? oneDark : []) });
  },
  get() { return view.state; },
  text() { return view.state.doc.toString(); },
  lang(key) { view.dispatch({ effects: langC.reconfigure((L[key] || L.txt)()) }); },
  theme(dark) {
    isDark = dark;
    if (view) view.dispatch({ effects: themeC.reconfigure(dark ? oneDark : []) });
  },
  undo() { undo(view); view.focus(); },
  redo() { redo(view); view.focus(); },
  find() { openSearchPanel(view); },
  measure() { if (view) view.requestMeasure(); },
  key: keyFor
};
