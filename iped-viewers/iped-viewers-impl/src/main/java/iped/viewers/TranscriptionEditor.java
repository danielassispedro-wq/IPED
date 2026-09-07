package iped.viewers;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.*;
import org.w3c.dom.*;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import iped.data.IItem;
import iped.properties.ExtraProperties;
import iped.viewers.api.AttachmentSearcher;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

/** Java-owned modal: page scripts never receive a database write API. */
final class TranscriptionEditor {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "transcription-corrections");
        thread.setDaemon(true);
        return thread;
    });
    private final WebEngine engine;
    private final Document document;
    private final List<Row> rows = new ArrayList<>();
    private final List<EventListener> listeners = new ArrayList<>();
    private TranscriptionCorrectionStore store;

    private static final class Row {
        Element label, text, button;
        String source, item, hash;
        TranscriptionCorrectionStore.Entry entry;
        boolean opening;
    }

    private TranscriptionEditor(WebEngine engine) {
        this.engine = engine;
        document = engine.getDocument();
    }

    static TranscriptionEditor install(WebEngine engine, AttachmentSearcher searcher) {
        TranscriptionEditor editor = new TranscriptionEditor(engine);
        editor.initialize(searcher);
        return editor;
    }

    private boolean active() {
        return engine.getDocument() == document;
    }

    private void initialize(AttachmentSearcher searcher) {
        if (document == null) return;
        NodeList spans = document.getElementsByTagName("span");
        // Snapshot before inserting controls (NodeList is live).
        List<Element> containers = new ArrayList<>();
        for (int i = 0; i < spans.getLength(); i++) {
            Element span = (Element) spans.item(i);
            if ("iped-transcription".equals(span.getAttribute("class"))) containers.add(span);
        }
        if (containers.isEmpty()) return;
        Element style = document.createElement("style");
        style.setTextContent(".iped-transcription .iped-correct{visibility:hidden;margin-left:8px;cursor:pointer}"
                + ".iped-transcription:hover .iped-correct,.iped-transcription:focus-within .iped-correct,"
                + ".iped-transcription .iped-correct:focus{visibility:visible}"
                + ".iped-transcription-text{white-space:pre-wrap}");
        document.getDocumentElement().appendChild(style);
        for (Element container : containers) {
            Row row = new Row();
            for (Node child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element) {
                    Element element = (Element) child;
                    if ("iped-transcription-label".equals(element.getAttribute("class"))) row.label = element;
                    if ("iped-transcription-text".equals(element.getAttribute("class"))) row.text = element;
                }
            }
            if (row.label == null || row.text == null) continue;
            row.source = container.getAttribute("data-source");
            row.item = container.getAttribute("data-item");
            row.hash = container.getAttribute("data-hash");
            if (!row.item.matches("[0-9]+") || !row.hash.matches("[a-fA-F0-9]{16,128}")
                    || row.source.isBlank()) continue;
            row.button = document.createElement("button");
            row.button.setAttribute("type", "button");
            row.button.setAttribute("class", "iped-correct");
            row.button.setAttribute("disabled", "disabled");
            row.button.setAttribute("title", "Carregando correções externas...");
            row.button.setTextContent("\u270f\ufe0f Corrigir");
            container.appendChild(row.button);
            EventListener click = event -> {
                event.preventDefault();
                event.stopPropagation();
                if (active() && row.entry != null && !row.opening) open(row);
            };
            listeners.add(click); // Keep native DOM callbacks strongly reachable.
            ((EventTarget) row.button).addEventListener("click", click, false);
            rows.add(row);
        }
        final String directory = System.getProperty(TranscriptionCorrectionStore.DIRECTORY_PROPERTY);
        final List<java.io.File> caseDirectories = new ArrayList<>(searcher.getCaseDirectories());
        CompletableFuture.runAsync(() -> {
            try {
                if (directory == null || directory.isBlank()) {
                    throw new IllegalStateException("Configure -Diped.transcriptionCorrectionsDir com uma pasta externa ao caso.");
                }
                store = new TranscriptionCorrectionStore(Paths.get(directory), caseDirectories);
                for (Row row : rows) {
                    try {
                        IItem match = null;
                        for (IItem item : searcher.getItems("hash:" + row.hash)) {
                            if (Integer.toString(item.getId()).equals(row.item) && item.getDataSource() != null
                                    && row.source.equals(item.getDataSource().getUUID())
                                    && row.hash.equalsIgnoreCase(item.getHash())) {
                                if (match != null) throw new IllegalStateException("Identificação ambígua do áudio.");
                                match = item;
                            }
                        }
                        if (match == null) throw new IllegalStateException("Áudio original não encontrado no caso.");
                        String original = match.getMetadata().get(ExtraProperties.TRANSCRIPT_ATTR);
                        if (original == null) throw new IllegalStateException("Transcrição original indisponível.");
                        TranscriptionCorrectionStore.Entry entry = new TranscriptionCorrectionStore.Entry(
                                row.source, row.item, row.hash.toLowerCase(java.util.Locale.ROOT), original);
                        TranscriptionCorrectionStore.Revision revision = store.read(entry);
                        Platform.runLater(() -> {
                            if (!active()) return;
                            row.entry = entry;
                            render(row, revision);
                            row.button.removeAttribute("disabled");
                            row.button.setAttribute("title", "Corrigir transcrição sem alterar a evidência");
                        });
                    } catch (Exception e) {
                        unavailable(row, e.getMessage());
                    }
                }
            } catch (Exception e) {
                for (Row row : rows) unavailable(row, e.getMessage());
            }
        }, IO);
    }

    private void unavailable(Row row, String message) {
        Platform.runLater(() -> {
            if (!active()) return;
            row.button.setAttribute("title", "Correção indisponível: " + message);
        });
    }

    private void render(Row row, TranscriptionCorrectionStore.Revision revision) {
        // textContent treats HTML, quotes, script tags and line breaks as plain text.
        row.text.setTextContent(revision.text);
        if (revision.number > 0) row.label.setTextContent("Transcrição: \u2713 corrigida pelo perito");
    }

    private void open(Row row) {
        row.opening = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return store.read(row.entry);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, IO).whenComplete((revision, failure) -> Platform.runLater(() -> {
            if (!active()) { row.opening = false; return; }
            if (failure != null) {
                row.opening = false;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Não foi possível ler as correções. " + failure.getCause().getMessage(),
                        "Correção de transcrição", JOptionPane.ERROR_MESSAGE));
                return;
            }
            SwingUtilities.invokeLater(() -> showDialog(row, revision));
        }));
    }

    private void showDialog(Row row, TranscriptionCorrectionStore.Revision revision) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Corrigir transcrição", true);
        JTextArea original = new JTextArea(row.entry.original, 7, 65);
        original.setEditable(false);
        original.setLineWrap(true);
        original.setWrapStyleWord(true);
        JTextArea corrected = new JTextArea(revision.text, 7, 65);
        corrected.setLineWrap(true);
        corrected.setWrapStyleWord(true);
        String osUser = System.getProperty("user.name", "desconhecido");
        JTextField examiner = new JTextField(osUser);
        JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));
        fields.add(new JLabel("Original automático (somente leitura)"));
        fields.add(new JScrollPane(original));
        fields.add(new JLabel("Transcrição corrigida"));
        fields.add(new JScrollPane(corrected));
        JPanel identity = new JPanel(new BorderLayout());
        identity.add(new JLabel("Perito: "), BorderLayout.WEST);
        identity.add(examiner, BorderLayout.CENTER);
        fields.add(identity);
        fields.add(new JLabel("Fonte: " + row.entry.source + " | Item: " + row.entry.item));
        fields.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.add(fields, BorderLayout.CENTER);
        JButton save = new JButton("Salvar");
        JButton cancel = new JButton("Cancelar");
        JPanel buttons = new JPanel();
        buttons.add(save);
        buttons.add(cancel);
        dialog.add(buttons, BorderLayout.SOUTH);
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            String text = corrected.getText();
            String declared = examiner.getText().trim();
            if (declared.isEmpty() || text.isBlank() || text.length() > 1000000) {
                JOptionPane.showMessageDialog(dialog, "Informe o perito e a transcrição (até 1.000.000 caracteres).");
                return;
            }
            save.setEnabled(false);
            cancel.setEnabled(false);
            corrected.setEditable(false);
            examiner.setEditable(false);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            CompletableFuture.supplyAsync(() -> {
                try {
                    return store.save(row.entry, revision.number, text,
                            declared + " [usuário do sistema: " + osUser + "]");
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, IO).whenComplete((saved, failure) -> SwingUtilities.invokeLater(() -> {
                if (failure != null) {
                    JOptionPane.showMessageDialog(dialog,
                            "Correção não salva. " + failure.getCause().getMessage(),
                            "Correção de transcrição", JOptionPane.ERROR_MESSAGE);
                    save.setEnabled(true);
                    cancel.setEnabled(true);
                    corrected.setEditable(true);
                    examiner.setEditable(true);
                    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                } else {
                    Platform.runLater(() -> {
                        if (!active()) return;
                        for (Row other : rows) {
                            if (other.entry != null && other.entry.key.equals(row.entry.key)) render(other, saved);
                        }
                    });
                    dialog.dispose();
                }
            }));
        });
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().registerKeyboardAction(event -> {
            if (cancel.isEnabled()) dialog.dispose();
        }, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        try {
            dialog.setVisible(true);
        } finally {
            Platform.runLater(() -> row.opening = false);
        }
    }
}
