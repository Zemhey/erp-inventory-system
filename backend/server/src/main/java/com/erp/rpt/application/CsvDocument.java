package com.erp.rpt.application;

/**
 * 服务端生成的 CSV 文档（文件名 + 完整内容）。
 *
 * <p>内容以 UTF-8 BOM 开头、行尾 CRLF：Excel 双击直接打开不乱码，
 * 中文 Windows 环境也不需要用户先做「数据导入」。
 */
public record CsvDocument(String filename, String content) {
}
