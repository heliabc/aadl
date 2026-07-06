package com.nuaa.aadl.module.rag.service;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentParserService {

    public String parseDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    String text = ((XWPFParagraph) element).getText().trim();
                    if (!text.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cellTexts = new ArrayList<>();
                        for (int ci = 0; ci < row.getTableCells().size(); ci++) {
                            XWPFTableCell cell = row.getCell(ci);
                            if (cell == null) continue;
                            String cellText = cell.getText().trim();
                            if (!cellText.isEmpty()) {
                                cellTexts.add(cellText);
                            }
                        }
                        if (!cellTexts.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(String.join(" ", cellTexts));
                        }
                    }
                    sb.append("\n\n");
                }
            }
            return sb.toString();
        }
    }

    public String parseDoc(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    public String parseXlsx(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            for (int sheetNum = 0; sheetNum < workbook.getNumberOfSheets(); sheetNum++) {
                Sheet sheet = workbook.getSheetAt(sheetNum);
                String sheetName = sheet.getSheetName();
                
                sb.append("=== Sheet: ").append(sheetName).append(" ===\n");
                
                for (Row row : sheet) {
                    if (row == null) continue;
                    
                    List<String> rowData = new ArrayList<>();
                    for (Cell cell : row) {
                        if (cell == null) continue;
                        
                        String cellValue = getCellValue(cell);
                        if (cellValue != null && !cellValue.isEmpty()) {
                            rowData.add(cellValue);
                        }
                    }
                    
                    if (!rowData.isEmpty()) {
                        sb.append(String.join(" | ", rowData)).append("\n");
                    }
                }
                sb.append("\n");
            }
        }
        
        return sb.toString().trim();
    }

    private String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                } else {
                    double numVal = cell.getNumericCellValue();
                    if (numVal == Math.floor(numVal)) {
                        yield String.valueOf((int) numVal);
                    } else {
                        yield String.valueOf(numVal);
                    }
                }
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        yield String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    public record ParsedDocument(String content, String fileName, String extension) {}
}
