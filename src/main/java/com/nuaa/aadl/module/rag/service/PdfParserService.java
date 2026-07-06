package com.nuaa.aadl.module.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfParserService {

    private net.sourceforge.tess4j.Tesseract tesseract;

    public PdfParserService() {
        try {
            tesseract = new net.sourceforge.tess4j.Tesseract();
            String tessDataPath = System.getenv("TESSDATA_PREFIX");
            if (tessDataPath != null) {
                tesseract.setDatapath(tessDataPath);
            }
        } catch (Exception e) {
            tesseract = null;
        }
    }

    public record ParsedPage(int pageNumber, String content) {}

    public List<ParsedPage> parsePdf(File pdfFile) throws IOException {
        List<ParsedPage> pages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int numPages = document.getNumberOfPages();
            for (int i = 1; i <= numPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);

                if (text != null && !text.trim().isEmpty()) {
                    pages.add(new ParsedPage(i, text.trim()));
                } else if (tesseract != null) {
                    String ocrText = extractTextByOcr(pdfFile, i);
                    if (ocrText != null && !ocrText.trim().isEmpty()) {
                        pages.add(new ParsedPage(i, ocrText.trim()));
                    }
                }
            }
        }

        return pages;
    }

    private String extractTextByOcr(File pdfFile, int pageNumber) {
        try {
            org.icepdf.core.pobjects.Document iceDoc = new org.icepdf.core.pobjects.Document();
            iceDoc.setFile(pdfFile.getAbsolutePath());

            BufferedImage image = (BufferedImage) iceDoc.getPageImage(
                pageNumber - 1,
                org.icepdf.core.util.GraphicsRenderingHints.SCREEN,
                org.icepdf.core.pobjects.Page.BOUNDARY_CROPBOX,
                0,
                1.0f
            );

            String result = tesseract.doOCR(image);
            image.flush();
            iceDoc.dispose();

            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public String parsePdfToText(File pdfFile) throws IOException {
        List<ParsedPage> pages = parsePdf(pdfFile);
        StringBuilder sb = new StringBuilder();
        for (ParsedPage page : pages) {
            sb.append(page.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
