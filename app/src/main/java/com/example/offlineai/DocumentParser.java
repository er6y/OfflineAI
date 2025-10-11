package com.example.offlineai;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShowImpl;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.tika.Tika;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.openxml4j.opc.OPCPackage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 文档解析器，用于从不同类型的文档中提取文本
 * 支持文本、PDF和Office文档
 */
public class DocumentParser {
    private static final String TAG = "OfflineAI_DocParser";
    
    private final Context context;
    private final Tika tika;
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public DocumentParser(Context context) {
        this.context = context;
        this.tika = new Tika();
        
        // 设置ZIP文件的最小膨胀比例，解决Office文档中的ZIP炸弹检测问题
        // 默认值是0.01，降低到0.001允许更高的压缩比
        ZipSecureFile.setMinInflateRatio(0.001);
        LogManager.logD(TAG, "已设置ZipSecureFile的最小膨胀比例为0.001");
        LogManager.logD(TAG, "DocumentParser初始化完成，已优化资源管理避免泄漏");
    }
    
    /**
     * 从文件URI中提取文本
     * @param uri 文件URI
     * @return 提取的文本内容
     */
    public String extractText(Uri uri) {
        try {
            String mimeType = detectMimeType(uri);
            String fileName = UriUtils.getFileName(context, uri);
            LogManager.logD(TAG, "文件类型: " + mimeType + ", 文件名: " + fileName);
            
            // 根据文件类型选择合适的解析方法
            if (isOfficeDocument(fileName) || mimeType.contains("officedocument") || mimeType.contains("msword") || 
                mimeType.contains("application/vnd.openxmlformats") || mimeType.contains("application/x-tika-ooxml")) {
                try {
                    return extractFromOfficeDocument(uri, fileName);
                } catch (Exception e) {
                    LogManager.logE(TAG, "Office文档处理失败，尝试使用Tika: " + e.getMessage(), e);
                    // 使用Tika作为备用方法
                    try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                        if (inputStream != null) {
                            LogManager.logD(TAG, "[RESOURCE-TIKA] 开始使用 Tika 解析（备用方法）");
                            String tikaText = tika.parseToString(inputStream);
                            LogManager.logD(TAG, "[RESOURCE-TIKA] Tika 解析完成（备用方法）");
                            // Tika 提取的内容需要激进清理
                            return cleanText(tikaText, true);
                        } else {
                            throw new Exception("无法打开文件流");
                        }
                    }
                }
            } else if (isPdfDocument(fileName) || "application/pdf".equals(mimeType)) {
                return extractFromPdf(uri);
            } else {
                // 对于其他类型，尝试作为文本文件读取
                return extractFromTextFile(uri);
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "提取文本失败: " + e.getMessage(), e);
            return "【文本提取失败】" + e.getMessage();
        }
    }
    
    /**
     * 检测文件的MIME类型（使用轻量级方法避免资源泄漏）
     */
    private String detectMimeType(Uri uri) {
        // 直接使用 Android 系统方法，避免 Tika.detect() 导致的资源泄漏
        // Tika.detect() 会打开 ZipFile/OPCPackage 等资源但不正确关闭
        String mimeType = getMimeType(uri);
        LogManager.logD(TAG, "检测到的MIME类型: " + mimeType);
        return mimeType;
    }
    
    /**
     * 获取文件的MIME类型（使用Android系统方法）
     */
    private String getMimeType(Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        if (mimeType == null) {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase());
            }
        }
        return mimeType != null ? mimeType : "application/octet-stream";
    }
    
    /**
     * 判断是否为Office文档
     */
    private boolean isOfficeDocument(String fileName) {
        if (fileName == null) return false;
        String lowerCase = fileName.toLowerCase();
        return lowerCase.endsWith(".doc") || lowerCase.endsWith(".docx") || 
               lowerCase.endsWith(".xls") || lowerCase.endsWith(".xlsx") || 
               lowerCase.endsWith(".ppt") || lowerCase.endsWith(".pptx");
    }
    
    /**
     * 判断是否为PDF文档
     */
    private boolean isPdfDocument(String fileName) {
        if (fileName == null) return false;
        return fileName.toLowerCase().endsWith(".pdf");
    }
    
    /**
     * 从Office文档中提取文本
     */
    private String extractFromOfficeDocument(Uri uri, String fileName) throws Exception {
        LogManager.logD(TAG, "[RESOURCE] ========== 开始处理 Office 文档: " + fileName + " ==========");
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        
        if (inputStream == null) {
            throw new Exception("无法打开文件流");
        }
        
        try {
            String lowerCase = fileName.toLowerCase();
            StringBuilder text = new StringBuilder();
            boolean usedTika = false;  // 追踪是否使用了 Tika
            
            // 主要依赖文件扩展名判断类型，避免使用 Tika.detect() 导致资源泄漏
            // Tika.detect() 会打开 ZipFile/OPCPackage 等资源但不正确关闭
            String detectedType = "";
            LogManager.logD(TAG, "使用文件扩展名判断文档类型，避免资源泄漏");
            
            // 使用文件扩展名判断类型（更可靠且避免资源泄漏）
            boolean isDocx = lowerCase.endsWith(".docx");
            boolean isDoc = lowerCase.endsWith(".doc");
            boolean isXlsx = lowerCase.endsWith(".xlsx");
            boolean isXls = lowerCase.endsWith(".xls");
            boolean isPptx = lowerCase.endsWith(".pptx");
            boolean isPpt = lowerCase.endsWith(".ppt");
            
            LogManager.logD(TAG, "Document type detection: isDocx=" + isDocx + ", isDoc=" + isDoc + 
                           ", isXlsx=" + isXlsx + ", isXls=" + isXls + 
                           ", isPptx=" + isPptx + ", isPpt=" + isPpt);
            
            if (isDoc) {
                // 处理DOC文件
                LogManager.logD(TAG, "Using Apache POI HWPFDocument for .doc file");
                try (HWPFDocument doc = new HWPFDocument(inputStream);
                     WordExtractor extractor = new WordExtractor(doc)) {
                    text.append(extractor.getText());
                }
            } else if (isDocx) {
                // 处理DOCX文件 - 使用 OPCPackage.open() 方式避免 XWPFDocument 构造函数的资源泄漏
                LogManager.logD(TAG, "Using Apache POI XWPFDocument for .docx file (via OPCPackage)");
                OPCPackage opcPackage = null;
                XWPFDocument docx = null;
                XWPFWordExtractor extractor = null;
                try {
                    LogManager.logD(TAG, "[RESOURCE] 开始创建 OPCPackage");
                    // 先创建 OPCPackage，这样可以更好地控制资源
                    opcPackage = OPCPackage.open(inputStream);
                    LogManager.logD(TAG, "[RESOURCE] OPCPackage 创建完成");
                    
                    LogManager.logD(TAG, "[RESOURCE] 开始创建 XWPFDocument (from OPCPackage)");
                    docx = new XWPFDocument(opcPackage);
                    LogManager.logD(TAG, "[RESOURCE] XWPFDocument 创建完成");
                    
                    extractor = new XWPFWordExtractor(docx);
                    LogManager.logD(TAG, "[RESOURCE] XWPFWordExtractor 创建完成");
                    
                    text.append(extractor.getText());
                    LogManager.logD(TAG, "[RESOURCE] 文本提取完成，准备关闭资源");
                } finally {
                    LogManager.logD(TAG, "[RESOURCE] 进入 finally 块，开始关闭资源");
                    
                    if (extractor != null) {
                        try { 
                            LogManager.logD(TAG, "[RESOURCE] 关闭 extractor");
                            extractor.close(); 
                            LogManager.logD(TAG, "[RESOURCE] extractor 已关闭");
                        } catch (Exception e) { 
                            LogManager.logE(TAG, "[RESOURCE] 关闭 extractor 失败: " + e.getMessage());
                        }
                    }
                    
                    if (docx != null) {
                        try { 
                            LogManager.logD(TAG, "[RESOURCE] 关闭 docx");
                            docx.close(); 
                            LogManager.logD(TAG, "[RESOURCE] docx 已关闭");
                        } catch (Exception e) { 
                            LogManager.logE(TAG, "[RESOURCE] 关闭 docx 失败: " + e.getMessage());
                        }
                    }
                    
                    // 最关键：必须显式关闭 OPCPackage（我们自己创建的）
                    if (opcPackage != null) {
                        try { 
                            LogManager.logD(TAG, "[RESOURCE] 关闭 OPCPackage");
                            opcPackage.close(); 
                            LogManager.logD(TAG, "[RESOURCE] OPCPackage 已关闭");
                        } catch (Exception e) { 
                            LogManager.logE(TAG, "[RESOURCE] 关闭 OPCPackage 失败: " + e.getMessage());
                        }
                    }
                    
                    LogManager.logD(TAG, "[RESOURCE] finally 块执行完毕");
                }
            } else if (isXls) {
                // 处理XLS文件
                LogManager.logD(TAG, "Using Apache POI HSSFWorkbook for .xls file");
                try (HSSFWorkbook workbook = new HSSFWorkbook(inputStream)) {
                    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                    
                    // 提取每个工作表的内容
                    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                        HSSFSheet sheet = workbook.getSheetAt(sheetIndex);
                        String sheetName = workbook.getSheetName(sheetIndex);
                        text.append("工作表: ").append(sheetName).append("\n");
                        
                        // 提取每行内容
                        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                            HSSFRow row = sheet.getRow(rowIndex);
                            if (row != null) {
                                StringBuilder rowText = new StringBuilder();
                                for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                                    HSSFCell cell = row.getCell(cellIndex);
                                    if (cell != null) {
                                        try {
                                            String cellValue = getCellValueAsString(cell, evaluator);
                                            if (cellValue != null && !cellValue.trim().isEmpty()) {
                                                rowText.append(cellValue).append("\t");
                                            }
                                        } catch (Exception e) {
                                            LogManager.logE(TAG, "提取单元格内容失败: " + e.getMessage());
                                        }
                                    }
                                }
                                if (rowText.length() > 0) {
                                    text.append(rowText).append("\n");
                                }
                            }
                        }
                        text.append("\n");
                    }
                } catch (Exception e) {
                    // 如果POI处理失败，回退到使用Tika
                    LogManager.logE(TAG, "使用POI处理XLS文件失败，回退到使用Tika: " + e.getMessage());
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    LogManager.logD(TAG, "[RESOURCE-TIKA] 开始使用 Tika 解析 XLS（回退）");
                    String tikaText = tika.parseToString(inputStream);
                    LogManager.logD(TAG, "[RESOURCE-TIKA] Tika 解析 XLS 完成（回退）");
                    usedTika = true;
                    text.append(tikaText);
                }
            } else if (isXlsx) {
                // 处理XLSX文件 - 使用 OPCPackage.open() 方式避免资源泄漏
                LogManager.logD(TAG, "Using Apache POI XSSFWorkbook for .xlsx file (via OPCPackage)");
                OPCPackage opcPackage = null;
                XSSFWorkbook workbook = null;
                try {
                    LogManager.logD(TAG, "[RESOURCE] 开始创建 OPCPackage");
                    opcPackage = OPCPackage.open(inputStream);
                    LogManager.logD(TAG, "[RESOURCE] OPCPackage 创建完成");
                    
                    LogManager.logD(TAG, "[RESOURCE] 开始创建 XSSFWorkbook (from OPCPackage)");
                    workbook = new XSSFWorkbook(opcPackage);
                    LogManager.logD(TAG, "[RESOURCE] XSSFWorkbook 创建完成");
                    
                    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                    
                    // 提取每个工作表的内容
                    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                        XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
                        String sheetName = workbook.getSheetName(sheetIndex);
                        text.append("工作表: ").append(sheetName).append("\n");
                        
                        // 提取每行内容
                        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                            XSSFRow row = sheet.getRow(rowIndex);
                            if (row != null) {
                                StringBuilder rowText = new StringBuilder();
                                for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                                    XSSFCell cell = row.getCell(cellIndex);
                                    if (cell != null) {
                                        try {
                                            String cellValue = getCellValueAsString(cell, evaluator);
                                            if (cellValue != null && !cellValue.trim().isEmpty()) {
                                                rowText.append(cellValue).append("\t");
                                            }
                                        } catch (Exception e) {
                                            LogManager.logE(TAG, "提取单元格内容失败: " + e.getMessage());
                                        }
                                    }
                                }
                                if (rowText.length() > 0) {
                                    text.append(rowText).append("\n");
                                }
                            }
                        }
                        text.append("\n");
                    }
                } catch (Exception e) {
                    // 如果POI处理失败，回退到使用Tika
                    LogManager.logE(TAG, "使用POI处理XLSX文件失败，回退到使用Tika: " + e.getMessage());
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    LogManager.logD(TAG, "[RESOURCE-TIKA] 开始使用 Tika 解析 XLSX（回退）");
                    String tikaText = tika.parseToString(inputStream);
                    LogManager.logD(TAG, "[RESOURCE-TIKA] Tika 解析 XLSX 完成（回退）");
                    usedTika = true;
                    text.append(tikaText);
                } finally {
                    LogManager.logD(TAG, "[RESOURCE] 进入 finally 块，开始关闭 XLSX 资源");
                    
                    if (workbook != null) {
                        try { 
                            LogManager.logD(TAG, "[RESOURCE] 关闭 workbook");
                            workbook.close(); 
                            LogManager.logD(TAG, "[RESOURCE] workbook 已关闭");
                        } catch (Exception e) { 
                            LogManager.logE(TAG, "[RESOURCE] 关闭 workbook 失败: " + e.getMessage());
                        }
                    }
                    
                    // 最关键：必须显式关闭 OPCPackage
                    if (opcPackage != null) {
                        try { 
                            LogManager.logD(TAG, "[RESOURCE] 关闭 OPCPackage");
                            opcPackage.close(); 
                            LogManager.logD(TAG, "[RESOURCE] OPCPackage 已关闭");
                        } catch (Exception e) { 
                            LogManager.logE(TAG, "[RESOURCE] 关闭 OPCPackage 失败: " + e.getMessage());
                        }
                    }
                    
                    LogManager.logD(TAG, "[RESOURCE] XLSX finally 块执行完毕");
                }
            } else if (isPpt || isPptx) {
                // 处理PPT/PPTX文件 - 直接使用 Tika（POI 在 Android 上有兼容性问题）
                LogManager.logD(TAG, "Using Tika for PPT/PPTX file (more reliable on Android)");
                LogManager.logD(TAG, "[RESOURCE-TIKA] 开始使用 Tika 解析 PPT/PPTX");
                String tikaText = tika.parseToString(inputStream);
                LogManager.logD(TAG, "[RESOURCE-TIKA] Tika 解析 PPT/PPTX 完成");
                usedTika = true;
                text.append(tikaText);
            } else {
                // 其他类型使用 Tika
                LogManager.logD(TAG, "Using Tika for other file types");
                LogManager.logD(TAG, "[RESOURCE-TIKA] 开始使用 Tika 解析其他类型");
                String tikaText = tika.parseToString(inputStream);
                LogManager.logD(TAG, "[RESOURCE-TIKA] Tika 解析其他类型完成");
                usedTika = true;
                text.append(tikaText);
            }
            
            // 将提取的文本保存到临时文件
            String rawText = text.toString();
            // 根据是否使用 Tika 选择不同的清理策略
            String cleanedText = cleanText(rawText, usedTika);
            
            // Log text extraction details for debugging
            LogManager.logD(TAG, "Office文档文本提取: 原始长度=" + rawText.length() + 
                           ", 清理后长度=" + cleanedText.length() + 
                           ", 清理后trim长度=" + cleanedText.trim().length());
            
            File tempFile = saveToTempFile(cleanedText, fileName);
            LogManager.logD(TAG, "已将Office文档内容保存到临时文件: " + tempFile.getAbsolutePath());
            
            // Warn if extracted text is abnormally large (may contain image/table metadata)
            if (cleanedText.length() > 500000) { // > 500KB
                LogManager.logW(TAG, "WARNING: Large text extracted from Office document (" + 
                               (cleanedText.length() / 1024) + "KB). " +
                               "File: " + fileName + ". " +
                               "This may indicate the document contains images, tables, or formatting metadata. " +
                               "Temp file for analysis: " + tempFile.getAbsolutePath());
            }
            
            LogManager.logD(TAG, "[RESOURCE] ========== Office 文档处理完成: " + fileName + " ==========");
            return cleanedText;
        } finally {
            try {
                LogManager.logD(TAG, "[RESOURCE] 关闭 Office 文档的 inputStream");
                inputStream.close();
                LogManager.logD(TAG, "[RESOURCE] Office 文档的 inputStream 已关闭");
            } catch (Exception e) {
                LogManager.logE(TAG, "[RESOURCE] 关闭输入流失败", e);
            }
        }
    }
    
    /**
     * 从PDF文档中提取文本
     */
    private String extractFromPdf(Uri uri) throws Exception {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        
        if (inputStream == null) {
            throw new Exception("无法打开文件流");
        }
        
        try {
            StringBuilder text = new StringBuilder();
            LogManager.logD(TAG, "[RESOURCE-PDF] 开始创建 PdfReader");
            PdfReader reader = new PdfReader(inputStream);
            LogManager.logD(TAG, "[RESOURCE-PDF] PdfReader 创建完成");
            int pages = reader.getNumberOfPages();
            LogManager.logD(TAG, "[RESOURCE-PDF] PDF 总页数: " + pages);
            
            for (int i = 1; i <= pages; i++) {
                String pageText = PdfTextExtractor.getTextFromPage(reader, i);
                text.append(pageText).append("\n");
            }
            
            LogManager.logD(TAG, "[RESOURCE-PDF] 关闭 PdfReader");
            reader.close();
            LogManager.logD(TAG, "[RESOURCE-PDF] PdfReader 已关闭");
            String cleanedText = cleanText(text.toString());
            
            // 将提取的文本保存到临时文件
            File tempFile = saveToTempFile(cleanedText, "pdf_extract.txt");
            LogManager.logD(TAG, "已将PDF文档内容保存到临时文件: " + tempFile.getAbsolutePath());
            
            return cleanedText;
        } finally {
            try {
                inputStream.close();
            } catch (Exception e) {
                LogManager.logE(TAG, "关闭输入流失败", e);
            }
        }
    }
    
    /**
     * 从文本文件中提取文本
     */
    private String extractFromTextFile(Uri uri) throws Exception {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        
        if (inputStream == null) {
            throw new Exception("无法打开文件流");
        }
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                text.append(line).append("\n");
            }
            
            return cleanText(text.toString());
        } finally {
            try {
                inputStream.close();
            } catch (Exception e) {
                LogManager.logE(TAG, "关闭输入流失败", e);
            }
        }
    }
    
    /**
     * 将文本保存到临时文件
     * @param text 要保存的文本
     * @param originalFileName 原始文件名，用于生成临时文件名
     * @return 临时文件对象
     */
    private File saveToTempFile(String text, String originalFileName) throws Exception {
        // 创建临时文件名
        String tempFileName = "temp_" + System.currentTimeMillis() + "_" + 
                              originalFileName.replaceAll("[^a-zA-Z0-9.-]", "_") + ".txt";
        
        // 获取应用的临时文件目录
        File tempDir = context.getCacheDir();
        File tempFile = new File(tempDir, tempFileName);
        
        // 写入文本内容
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8))) {
            writer.write(text);
        }
        
        return tempFile;
    }
    
    /**
     * 清理提取的文本，移除无用字符和格式
     * 注意: 保留换行符以维持文档结构
     * @param text 要清理的文本
     * @param isFromTika 是否来自 Tika 提取（Tika 需要更激进的清理）
     */
    public String cleanText(String text, boolean isFromTika) {
        if (text == null) return "";
        
        // Apache POI 提取的内容很干净，只做最基础的规范化
        if (!isFromTika) {
            LogManager.logD(TAG, "Minimal cleaning for Apache POI extracted text");
            
            // 只做基础规范化：统一换行符
            text = text.replaceAll("\\r\\n", "\n");
            text = text.replaceAll("\\r", "\n");
            
            // 压缩过多的空行
            text = text.replaceAll("\\n{3,}", "\n\n");
            
            return text.trim();
        }
        
        // Tika 提取的内容需要激进清理（因为 Tika 会提取图片/表格元数据）
        LogManager.logD(TAG, "Applying aggressive cleaning for Tika-extracted text");
        
        // 移除控制字符 (但保留换行符、回车符和制表符)
        text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        
        // 移除特殊Unicode字符
        text = text.replaceAll("[\\p{Cf}]", "");
        
        // === PPT/PPTX 特殊清理 ===
        // 移除图片元数据标记
        text = text.replaceAll("(?i)image\\d+\\.(png|jpg|jpeg|gif|bmp|tiff|emf|wmf|svg)", "");
        text = text.replaceAll("(?i)embedded\\s*image", "");
        text = text.replaceAll("(?i)\\[image:\\s*[^\\]]+\\]", "");
        text = text.replaceAll("(?i)picture\\s*\\d+", "");
        text = text.replaceAll("(?i)图片\\s*\\d+", "");
        
        // 移除 PPT 中的图表/表格标记
        text = text.replaceAll("(?i)chart\\s*\\d+", "");
        text = text.replaceAll("(?i)table\\s*\\d+", "");
        text = text.replaceAll("(?i)图表\\s*\\d+", "");
        text = text.replaceAll("(?i)表格\\s*\\d+", "");
        
        // 移除 PPT 中的形状/对象标记
        text = text.replaceAll("(?i)shape\\s*\\d+", "");
        text = text.replaceAll("(?i)object\\s*\\d+", "");
        text = text.replaceAll("(?i)textbox\\s*\\d+", "");
        
        // 移除 PPT 中的幻灯片编号标记（如果不需要的话）
        // text = text.replaceAll("(?i)slide\\s*\\d+", "");  // 可选：保留幻灯片编号可能有用
        
        // 移除 PPT 中的备注/注释标记
        text = text.replaceAll("(?i)notes?\\s*page", "");
        text = text.replaceAll("(?i)speaker\\s*notes?", "");
        
        // 移除Base64编码的图片数据 (长串字母数字)
        text = text.replaceAll("(?m)^[A-Za-z0-9+/=]{100,}$", "");
        
        // 移除XML/HTML标签残留
        text = text.replaceAll("<[^>]+>", "");
        
        // 移除表格边框字符和过多的制表符/空格
        text = text.replaceAll("[│┤├┼┴┬─]{2,}", "");
        text = text.replaceAll("\\t{3,}", "\t");
        text = text.replaceAll(" {5,}", " ");
        
        // 规范化换行符: 统一使用 \n
        text = text.replaceAll("\\r\\n", "\n");
        text = text.replaceAll("\\r", "\n");
        
        // 移除连续的空行 (3个以上连续换行符压缩为2个)
        text = text.replaceAll("\\n{3,}", "\n\n");
        
        // 移除每行首尾的空白字符,但保留换行符
        String[] lines = text.split("\\n");
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // 过滤垃圾行
            if (!trimmedLine.isEmpty() && !isGarbageLine(trimmedLine)) {
                cleaned.append(trimmedLine).append("\n");
            }
        }
        
        // 移除过长的重复字符序列（可能是二进制数据）
        String result = removeRepeatingPatterns(cleaned.toString());
        
        return result.trim();
    }
    
    /**
     * 兼容旧接口：默认不是从 Tika 提取
     */
    public String cleanText(String text) {
        return cleanText(text, false);
    }
    
    /**
     * 判断是否是垃圾行 (图片元数据、格式信息等)
     * 只用于 Tika 提取的内容，特别是 PPT/PPTX
     */
    private boolean isGarbageLine(String line) {
        // 过滤只包含特殊字符的行（但允许常见分隔符）
        if (line.matches("^[^a-zA-Z0-9\\u4e00-\\u9fa5\\-=_*#]+$")) {
            return true;
        }
        
        // 过滤看起来像十六进制数据的行
        if (line.length() > 20 && line.matches("^[0-9A-Fa-f\\s]{20,}$")) {
            return true;
        }
        
        // 过滤Base64编码的行
        if (line.length() > 50 && line.matches("^[A-Za-z0-9+/=]{50,}$")) {
            return true;
        }
        
        // === PPT/PPTX 特殊垃圾行检测 ===
        // 过滤图片文件名
        if (line.matches("(?i).*\\.(png|jpg|jpeg|gif|bmp|tiff|emf|wmf|svg)$")) {
            return true;
        }
        
        // 过滤只包含数字和点的行（可能是图片尺寸、坐标等）
        if (line.matches("^[\\d\\.\\s]+$") && line.length() < 30) {
            return true;
        }
        
        // 过滤 PPT 元数据关键词
        String lowerLine = line.toLowerCase();
        if (lowerLine.matches("^(image|picture|chart|table|shape|object|textbox|slide|notes?)\\s*\\d*$")) {
            return true;
        }
        
        // 过滤只包含单个字符重复的行（可能是边框）
        if (line.matches("^(.)\\1{5,}$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 移除文本中的重复模式
     */
    private String removeRepeatingPatterns(String text) {
        // 检测并移除连续重复超过10次的相同字符
        Pattern pattern = Pattern.compile("(.)\\1{10,}");
        return pattern.matcher(text).replaceAll("$1$1$1");
    }
    
    /**
     * 获取单元格的值作为字符串
     */
    private String getCellValueAsString(Cell cell, FormulaEvaluator evaluator) {
        CellType cellType = cell.getCellType();
        if (cellType == CellType.STRING) {
            return cell.getStringCellValue();
        } else if (cellType == CellType.NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        } else if (cellType == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        } else if (cellType == CellType.FORMULA) {
            return evaluator.evaluate(cell).formatAsString();
        } else {
            return "";
        }
    }
    
    /**
     * 安全地处理形状列表，避免因特定形状类型导致的崩溃
     */
    private void processShapesSafely(List<XSLFShape> shapes, StringBuilder text) {
        for (XSLFShape shape : shapes) {
            try {
                // 处理文本形状
                if (shape instanceof XSLFTextShape) {
                    try {
                        XSLFTextShape textShape = (XSLFTextShape) shape;
                        String shapeText = textShape.getText();
                        if (shapeText != null && !shapeText.trim().isEmpty()) {
                            text.append(shapeText).append("\n");
                        }
                    } catch (VerifyError | NoClassDefFoundError | UnsatisfiedLinkError e) {
                        LogManager.logE(TAG, "处理文本形状时出错: " + e.getMessage());
                    }
                }
                // 处理组形状，使用反射检查和处理
                else if (shape.getClass().getName().contains("GroupShape")) {
                    try {
                        // 使用反射获取子形状
                        Method getShapesMethod = shape.getClass().getMethod("getShapes");
                        @SuppressWarnings("unchecked")
                        List<XSLFShape> childShapes = (List<XSLFShape>) getShapesMethod.invoke(shape);
                        if (childShapes != null && !childShapes.isEmpty()) {
                            processShapesSafely(childShapes, text);
                        }
                    } catch (VerifyError | NoClassDefFoundError | UnsatisfiedLinkError e) {
                        LogManager.logE(TAG, "处理组形状时出错: " + e.getMessage());
                    } catch (Exception e) {
                        LogManager.logE(TAG, "通过反射处理组形状时出错: " + e.getMessage());
                    }
                }
                // 处理表格形状
                else if (shape.getClass().getName().contains("Table")) {
                    try {
                        // 使用反射安全地获取表格内容
                        Method getTextMethod = shape.getClass().getMethod("getText");
                        if (getTextMethod != null) {
                            String tableText = (String) getTextMethod.invoke(shape);
                            if (tableText != null && !tableText.trim().isEmpty()) {
                                text.append(tableText).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        LogManager.logE(TAG, "处理表格形状时出错: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "处理形状时出现未知错误: " + e.getMessage());
            }
        }
    }
    
    /**
     * 尝试直接从幻灯片XML中提取文本，避免使用可能不兼容的POI API
     */
    private String extractTextDirectlyFromSlide(XSLFSlide slide) {
        try {
            // 获取幻灯片的XML内容
            Method getXmlObjectMethod = slide.getClass().getMethod("getXmlObject");
            Object xmlObject = getXmlObjectMethod.invoke(slide);
            if (xmlObject != null) {
                // 获取XML内容的字符串表示
                String xmlContent = xmlObject.toString();
                
                // 提取所有<a:t>标签中的文本（这是PowerPoint XML中文本的标签）
                StringBuilder extractedText = new StringBuilder();
                Pattern pattern = Pattern.compile("<a:t>(.*?)</a:t>");
                Matcher matcher = pattern.matcher(xmlContent);
                
                while (matcher.find()) {
                    String textContent = matcher.group(1);
                    if (textContent != null && !textContent.trim().isEmpty()) {
                        extractedText.append(textContent).append("\n");
                    }
                }
                
                return extractedText.toString();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "直接从XML提取文本时出错: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 使用反射尝试获取幻灯片中的文本，作为最后的备用方法
     */
    private String extractTextUsingReflection(XSLFSlide slide) {
        try {
            StringBuilder extractedText = new StringBuilder();
            
            // 尝试使用反射获取幻灯片的内部XML结构
            Class<?> slideClass = slide.getClass();
            
            // 尝试获取幻灯片注释
            try {
                Method getNotesMethod = slideClass.getMethod("getNotes");
                Object notes = getNotesMethod.invoke(slide);
                if (notes != null) {
                    Method getTextMethod = notes.getClass().getMethod("getText");
                    String notesText = (String) getTextMethod.invoke(notes);
                    if (notesText != null && !notesText.trim().isEmpty()) {
                        extractedText.append("注释: ").append(notesText).append("\n");
                    }
                }
            } catch (Exception e) {
                LogManager.logD(TAG, "获取幻灯片注释失败: " + e.getMessage());
            }
            
            // 尝试获取幻灯片标题
            try {
                Method getTitleMethod = slideClass.getMethod("getTitle");
                String title = (String) getTitleMethod.invoke(slide);
                if (title != null && !title.trim().isEmpty()) {
                    extractedText.append("标题: ").append(title).append("\n");
                }
            } catch (Exception e) {
                LogManager.logD(TAG, "获取幻灯片标题失败: " + e.getMessage());
            }
            
            return extractedText.toString();
        } catch (Exception e) {
            LogManager.logE(TAG, "使用反射提取文本时出错: " + e.getMessage());
            return null;
        }
    }
}

