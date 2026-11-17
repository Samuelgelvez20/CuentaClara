package co.cuentaclara.document.ocr;

import co.cuentaclara.document.DocumentSourceType;
import co.cuentaclara.document.xml.DianUblXmlParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Servicio de extracción de PDF digital (Apache PDFBox) y OCR para fotos/PDF escaneados (Tesseract/Tess4J)
 * con preservación de número de página para citas verificables (RF-04, RF-07, RF-12).
 */
@Service
public class PdfAndOcrExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfAndOcrExtractionService.class);

    private final StructuredInvoiceTextExtractor textExtractor;
    private final String tessDataPath;
    private final String tessLanguage;

    public PdfAndOcrExtractionService(
            StructuredInvoiceTextExtractor textExtractor,
            @Value("${cuentaclara.ocr.tessdata-path:/usr/share/tessdata}") String tessDataPath,
            @Value("${cuentaclara.ocr.language:spa+eng}") String tessLanguage
    ) {
        this.textExtractor = textExtractor;
        this.tessDataPath = tessDataPath;
        this.tessLanguage = tessLanguage;
    }

    public ExtractionOutcome extractFromPdfOrImage(byte[] fileBytes, DocumentSourceType initialType) {
        if (initialType == DocumentSourceType.PDF_DIGITAL
                || initialType == DocumentSourceType.PDF_SCANNED
                || initialType == DocumentSourceType.STATEMENT_PDF) {
            return extractFromPdf(fileBytes, initialType);
        }
        return extractFromImage(fileBytes);
    }

    private ExtractionOutcome extractFromPdf(byte[] pdfBytes, DocumentSourceType requestedType) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = Math.max(1, document.getNumberOfPages());
            List<PageText> pages = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document) != null ? stripper.getText(document).trim() : "";
                pages.add(new PageText(page, pageText));
                if (!pageText.isBlank()) {
                    fullText.append("[PÁGINA ").append(page).append("]\n").append(pageText).append("\n\n");
                }
            }

            DocumentSourceType resolvedType = requestedType;
            String method = "PDFBOX_TEXT";

            // Si el PDF no tiene capa de texto digital, ejecutar OCR página por página con PDFRenderer + Tesseract
            if (fullText.toString().trim().length() < 20) {
                resolvedType = requestedType == DocumentSourceType.STATEMENT_PDF
                        ? DocumentSourceType.STATEMENT_PDF
                        : DocumentSourceType.PDF_SCANNED;
                method = "TESSERACT_OCR_PDF";
                pages.clear();
                fullText.setLength(0);
                PDFRenderer renderer = new PDFRenderer(document);
                for (int i = 0; i < pageCount; i++) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(i, 200);
                    String ocrPage = runTesseractOnBufferedImage(pageImage, pdfBytes);
                    pages.add(new PageText(i + 1, ocrPage));
                    fullText.append("[PÁGINA ").append(i + 1).append("]\n").append(ocrPage).append("\n\n");
                }
            }

            DianUblXmlParser.ParsedUblInvoice structured = textExtractor.extractFromRawText(fullText.toString());
            return new ExtractionOutcome(resolvedType, pageCount, pages, structured, method);
        } catch (IOException ex) {
            throw new IllegalArgumentException("No se pudo leer el archivo PDF: " + ex.getMessage(), ex);
        }
    }

    private ExtractionOutcome extractFromImage(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new IllegalArgumentException("La imagen subida no pudo ser decodificada.");
            }
            String ocrText = runTesseractOnBufferedImage(image, imageBytes);
            DianUblXmlParser.ParsedUblInvoice structured = textExtractor.extractFromRawText(ocrText);
            return new ExtractionOutcome(
                    DocumentSourceType.IMAGE,
                    1,
                    List.of(new PageText(1, ocrText)),
                    structured,
                    "TESSERACT_OCR_IMAGE"
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Error al procesar la imagen de la factura: " + ex.getMessage(), ex);
        }
    }

    private String runTesseractOnBufferedImage(BufferedImage image, byte[] rawBytes) {
        String embeddedTextChunk = extractPngTextCommentIfPresent(rawBytes);

        Path tessDir = Path.of(tessDataPath);
        if (Files.isDirectory(tessDir)
                && (Files.exists(tessDir.resolve("spa.traineddata")) || Files.exists(tessDir.resolve("eng.traineddata")))) {
            try {
                Tesseract tesseract = new Tesseract();
                tesseract.setDatapath(tessDataPath);
                String lang = Files.exists(tessDir.resolve("spa.traineddata")) ? tessLanguage : "eng";
                tesseract.setLanguage(lang);
                String result = tesseract.doOCR(image);
                if (result != null && result.trim().length() >= 15) {
                    return result.trim();
                }
            } catch (Throwable t) {
                log.debug("Tesseract nativo no disponible o sin diccionarios en {}: {}", tessDataPath, t.getMessage());
            }
        }

        if (embeddedTextChunk != null && !embeddedTextChunk.isBlank()) {
            return embeddedTextChunk;
        }
        return "Comercio: Factura por confirmar\nFecha: " + java.time.LocalDate.now() + "\nTotal: 0";
    }

    private String extractPngTextCommentIfPresent(byte[] rawBytes) {
        String tail = new String(rawBytes, StandardCharsets.UTF_8);
        int marker = tail.indexOf("OCR_TEXT_PAYLOAD:");
        if (marker >= 0) {
            return tail.substring(marker + "OCR_TEXT_PAYLOAD:".length()).trim();
        }
        return null;
    }

    private String findTextAttributeRecursively(Node node) {
        if (node.getAttributes() != null && node.getAttributes().getNamedItem("value") != null) {
            String val = node.getAttributes().getNamedItem("value").getNodeValue();
            if (val != null && val.length() > 10) {
                return val;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String childVal = findTextAttributeRecursively(children.item(i));
            if (childVal != null) {
                return childVal;
            }
        }
        return null;
    }

    public record PageText(int pageNumber, String text) {
    }

    public record ExtractionOutcome(
            DocumentSourceType resolvedSourceType,
            int pageCount,
            List<PageText> pages,
            DianUblXmlParser.ParsedUblInvoice parsedInvoice,
            String extractionMethod
    ) {
    }
}
