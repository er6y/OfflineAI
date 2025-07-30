package com.example.starlocalrag;

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
    private static final String TAG = "StarLocalRAG_DocParser";
    
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
    }
    
    /**
     * 从文件URI中提取文本
     * @param uri 文件URI
     * @return 提取的文本内容
     */
    public String extractText(Uri uri) {
        try {
            String mimeType = detectMimeType(uri);
            String fileName = getFileName(uri);
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
                            return tika.parseToString(inputStream);
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
     * 使用Tika检测文件的MIME类型
     */
    private String detectMimeType(Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                LogManager.logE(TAG, "无法打开文件流");
                return getMimeType(uri); // 回退到Android系统的MIME类型检测
            }
            
            return tika.detect(inputStream);
        } catch (Exception e) {
            LogManager.logE(TAG, "Tika检测MIME类型失败: " + e.getMessage(), e);
            return getMimeType(uri); // 回退到Android系统的MIME类型检测
        }
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
     * 获取文件名
     */
    private String getFileName(Uri uri) {
        String result = uri.getLastPathSegment();
        if (result != null && result.contains("/")) {
            result = result.substring(result.lastIndexOf("/") + 1);
        }
        return result != null ? result : "unknown";
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
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        
        if (inputStream == null) {
            throw new Exception("无法打开文件流");
        }
        
        try {
            String lowerCase = fileName.toLowerCase();
            StringBuilder text = new StringBuilder();
            
            // 首先尝试使用Tika检测实际的文件类型，避免仅依赖文件扩展名
            try {
                String detectedType = tika.detect(inputStream);
                LogManager.logD(TAG, "Tika检测到的文件类型: " + detectedType);
                
                // 如果检测到的类型与扩展名不匹配，记录警告
                if (!detectedType.contains("officedocument") && 
                    !detectedType.contains("msword") && 
                    !detectedType.contains("application/vnd.openxmlformats") &&
                    !detectedType.contains("application/x-tika-ooxml") &&
                    !detectedType.contains("application/vnd.ms-") &&
                    !detectedType.equals("application/octet-stream")) {
                    
                    LogManager.logW(TAG, "文件扩展名与实际内容类型不匹配: 扩展名表明是Office文档，但实际类型是 " + detectedType);
                    
                    // 如果是文本类型，直接使用Tika解析
                    if (detectedType.contains("text/") || 
                        detectedType.contains("application/json") || 
                        detectedType.contains("application/xml")) {
                        
                        // 重置流
                        inputStream.close();
                        inputStream = context.getContentResolver().openInputStream(uri);
                        if (inputStream == null) {
                            throw new Exception("无法重新打开文件流");
                        }
                        
                        String tikaText = tika.parseToString(inputStream);
                        return tikaText;
                    }
                }
                
                // 重置流
                inputStream.close();
                inputStream = context.getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    throw new Exception("无法重新打开文件流");
                }
            } catch (Exception e) {
                LogManager.logE(TAG, "使用Tika检测文件类型失败: " + e.getMessage());
                // 重置流
                inputStream.close();
                inputStream = context.getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    throw new Exception("无法重新打开文件流");
                }
            }
            
            if (lowerCase.endsWith(".doc")) {
                // 处理DOC文件
                HWPFDocument doc = new HWPFDocument(inputStream);
                WordExtractor extractor = new WordExtractor(doc);
                text.append(extractor.getText());
                extractor.close();
            } else if (lowerCase.endsWith(".docx")) {
                // 处理DOCX文件
                XWPFDocument docx = new XWPFDocument(inputStream);
                XWPFWordExtractor extractor = new XWPFWordExtractor(docx);
                text.append(extractor.getText());
                extractor.close();
                docx.close();
            } else if (lowerCase.endsWith(".xls")) {
                // 处理XLS文件
                try {
                    // 尝试使用POI处理
                    HSSFWorkbook workbook = new HSSFWorkbook(inputStream);
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
                    workbook.close();
                } catch (Exception e) {
                    // 如果POI处理失败，回退到使用Tika
                    LogManager.logE(TAG, "使用POI处理XLS文件失败，回退到使用Tika: " + e.getMessage());
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    String tikaText = tika.parseToString(inputStream);
                    text.append(tikaText);
                }
            } else if (lowerCase.endsWith(".xlsx")) {
                // 处理XLSX文件
                try {
                    // 尝试使用POI处理
                    XSSFWorkbook workbook = new XSSFWorkbook(inputStream);
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
                    workbook.close();
                } catch (Exception e) {
                    // 如果POI处理失败，回退到使用Tika
                    LogManager.logE(TAG, "使用POI处理XLSX文件失败，回退到使用Tika: " + e.getMessage());
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    String tikaText = tika.parseToString(inputStream);
                    text.append(tikaText);
                }
            } else if (lowerCase.endsWith(".ppt") || lowerCase.endsWith(".pptx")) {
                // 处理PPT/PPTX文件
                try {
                    if (lowerCase.endsWith(".ppt")) {
                        // 处理PPT文件
                        LogManager.logD(TAG, "使用HSLFSlideShow处理PPT文件: " + fileName);
                        HSLFSlideShowImpl slideShow = new HSLFSlideShowImpl(inputStream);
                        HSLFSlideShow ppt = new HSLFSlideShow(slideShow);
                        
                        // 提取每张幻灯片的文本
                        List<HSLFSlide> slides = ppt.getSlides();
                        for (int i = 0; i < slides.size(); i++) {
                            HSLFSlide slide = slides.get(i);
                            text.append("幻灯片 ").append(i + 1).append(":\n");
                            
                            // 提取形状中的文本
                            for (HSLFShape shape : slide.getShapes()) {
                                if (shape instanceof HSLFTextShape) {
                                    HSLFTextShape textShape = (HSLFTextShape) shape;
                                    String shapeText = textShape.getText();
                                    if (shapeText != null && !shapeText.trim().isEmpty()) {
                                        text.append(shapeText).append("\n");
                                    }
                                }
                            }
                            text.append("\n");
                        }
                        ppt.close();
                    } else {
                        // 处理PPTX文件
                        LogManager.logD(TAG, "使用XMLSlideShow处理PPTX文件: " + fileName);
                        XMLSlideShow pptx = new XMLSlideShow(inputStream);
                        
                        // 提取每张幻灯片的文本
                        List<XSLFSlide> slides = pptx.getSlides();
                        for (int i = 0; i < slides.size(); i++) {
                            try {
                                XSLFSlide slide = slides.get(i);
                                text.append("幻灯片 ").append(i + 1).append(":\n");
                                
                                // 使用更安全的方式提取幻灯片文本
                                try {
                                    // 方法1：尝试直接从幻灯片XML中提取文本，避免使用可能不兼容的POI API
                                    try {
                                        String slideText = extractTextDirectlyFromSlide(slide);
                                        if (slideText != null && !slideText.trim().isEmpty()) {
                                            text.append(slideText).append("\n");
                                            continue; // 如果成功提取，则跳过其他方法
                                        }
                                    } catch (Exception e) {
                                        LogManager.logD(TAG, "直接从幻灯片XML提取文本失败，尝试其他方法: " + e.getMessage());
                                    }
                                    
                                    // 方法2：使用安全的方式获取形状
                                    List<XSLFShape> shapes = null;
                                    try {
                                        shapes = slide.getShapes();
                                    } catch (VerifyError | NoClassDefFoundError | UnsatisfiedLinkError ve) {
                                        LogManager.logE(TAG, "获取幻灯片形状时出现错误，尝试备用方法: " + ve.getMessage());
                                        // 无法获取形状，跳过形状处理
                                        shapes = null;
                                    }
                                    
                                    if (shapes != null) {
                                        // 安全地处理每个形状
                                        processShapesSafely(shapes, text);
                                    } else {
                                        // 备用方法：尝试使用反射获取幻灯片中的文本
                                        String reflectionText = extractTextUsingReflection(slide);
                                        if (reflectionText != null && !reflectionText.trim().isEmpty()) {
                                            text.append(reflectionText).append("\n");
                                        } else {
                                            text.append("无法提取此幻灯片中的文本内容。\n");
                                        }
                                    }
                                } catch (VerifyError ve) {
                                    // 捕获VerifyError，这可能是由于Android平台不支持某些Java AWT类引起的
                                    LogManager.logE(TAG, "处理幻灯片形状时出现VerifyError: " + ve.getMessage(), ve);
                                    text.append("无法提取此幻灯片中的所有内容，可能包含不支持的元素。\n");
                                } catch (NoClassDefFoundError ncdfe) {
                                    // 捕获NoClassDefFoundError，这可能是由于类加载问题引起的
                                    LogManager.logE(TAG, "处理幻灯片形状时出现NoClassDefFoundError: " + ncdfe.getMessage(), ncdfe);
                                    text.append("无法提取此幻灯片中的所有内容，可能包含不支持的元素。\n");
                                } catch (UnsatisfiedLinkError ule) {
                                    // 捕获UnsatisfiedLinkError，这可能是由于本地库问题引起的
                                    LogManager.logE(TAG, "处理幻灯片形状时出现UnsatisfiedLinkError: " + ule.getMessage(), ule);
                                    text.append("无法提取此幻灯片中的所有内容，可能包含不支持的元素。\n");
                                } catch (Exception e) {
                                    LogManager.logE(TAG, "处理幻灯片形状时出错: " + e.getMessage(), e);
                                    text.append("处理此幻灯片时出现错误。\n");
                                }
                                
                                text.append("\n");
                            } catch (Exception e) {
                                LogManager.logE(TAG, "处理幻灯片 " + (i + 1) + " 时出错: " + e.getMessage(), e);
                                text.append("无法提取幻灯片 ").append(i + 1).append(" 的内容\n\n");
                            }
                        }
                        pptx.close();
                    }
                } catch (VerifyError ve) {
                    // 捕获VerifyError，这可能是由于Android平台不支持某些Java AWT类引起的
                    LogManager.logE(TAG, "处理PPT/PPTX文件时出现VerifyError，回退到使用Tika: " + ve.getMessage(), ve);
                    // 回退到使用Tika
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    LogManager.logD(TAG, "回退使用Tika解析PPT/PPTX文件: " + fileName);
                    String tikaText = tika.parseToString(inputStream);
                    text.append(tikaText);
                } catch (Exception e) {
                    // 如果POI处理失败，回退到使用Tika
                    LogManager.logE(TAG, "使用POI处理PPT/PPTX文件失败，回退到使用Tika: " + e.getMessage(), e);
                    inputStream.close();
                    inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        throw new Exception("无法重新打开文件流");
                    }
                    
                    // 使用Tika尝试提取文本
                    String tikaText = tika.parseToString(inputStream);
                    text.append(tikaText);
                }
            } else {
                // 对于其他Office文档类型，尝试使用Tika
                inputStream.close();
                inputStream = context.getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    throw new Exception("无法重新打开文件流");
                }
                
                // 使用Tika尝试提取文本
                String tikaText = tika.parseToString(inputStream);
                text.append(tikaText);
            }
            
            // 将提取的文本保存到临时文件
            String cleanedText = cleanText(text.toString());
            File tempFile = saveToTempFile(cleanedText, fileName);
            LogManager.logD(TAG, "已将Office文档内容保存到临时文件: " + tempFile.getAbsolutePath());
            
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
     * 从PDF文档中提取文本
     */
    private String extractFromPdf(Uri uri) throws Exception {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        
        if (inputStream == null) {
            throw new Exception("无法打开文件流");
        }
        
        try {
            StringBuilder text = new StringBuilder();
            PdfReader reader = new PdfReader(inputStream);
            int pages = reader.getNumberOfPages();
            
            for (int i = 1; i <= pages; i++) {
                String pageText = PdfTextExtractor.getTextFromPage(reader, i);
                text.append(pageText).append("\n");
            }
            
            reader.close();
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
     */
    public String cleanText(String text) {
        if (text == null) return "";
        
        // 移除连续的空白字符
        text = text.replaceAll("\\s+", " ");
        
        // 移除控制字符
        text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        
        // 移除特殊Unicode字符
        text = text.replaceAll("[\\p{Cf}]", "");
        
        // 移除过长的重复字符序列（可能是二进制数据）
        text = removeRepeatingPatterns(text);
        
        return text.trim();
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
