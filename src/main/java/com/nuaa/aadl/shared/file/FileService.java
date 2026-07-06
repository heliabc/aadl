package com.nuaa.aadl.shared.file;

import com.nuaa.aadl.app.config.AppProperties;
import com.nuaa.aadl.module.rag.service.DocumentParserService;
import com.nuaa.aadl.module.rag.service.PdfParserService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileService {

    private static final Set<String> SUPPORTED_UPLOAD_EXTENSIONS = Set.of(
            ".aadl",
            ".txt",
            ".docx",
            ".doc",
            ".pdf"
    );

    private final AppProperties appProperties;
    private final FileRepository fileRepository;

    public FileService(AppProperties appProperties, FileRepository fileRepository) {
        this.appProperties = appProperties;
        this.fileRepository = fileRepository;
    }

    // 原有的 upload 方法改为调用新的处理逻辑
    public UploadFilesResponse upload(List<MultipartFile> files) throws IOException {
        List<UploadedFileResponse> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            uploaded.addAll(processFile(file));
        }
        return new UploadFilesResponse(uploaded);
    }

    // 处理单个文件（支持 .aadl 和 .zip）
    private List<UploadedFileResponse> processFile(MultipartFile file) throws IOException {
        List<UploadedFileResponse> result = new ArrayList<>();
        String originalName = file.getOriginalFilename();
        if (originalName == null) return result;

        if (originalName.toLowerCase().endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".aadl")) {
                        byte[] content = zis.readAllBytes();
                        MultipartFile innerFile = byteArrayToMultipartFile(content, entry.getName());
                        result.addAll(processFile(innerFile)); // 递归处理（其实不会再是zip）
                    }
                    zis.closeEntry();
                }
            }
        } else if (isSupportedUploadFile(originalName.toLowerCase(Locale.ROOT))) {
            result.add(storeSingleFile(file));
            
        }  else if (originalName.toLowerCase().endsWith(".txt")) {
    // 支持 .txt 需求文件
    result.add(storeSingleFile(file));
}else {
            // 忽略不支持的类型，可根据需要抛出异常
            System.out.println("忽略不支持的文件: " + originalName);
        }
        return result;
    }

    // 存储单个 .aadl 文件
    private boolean isSupportedUploadFile(String normalizedName) {
        return SUPPORTED_UPLOAD_EXTENSIONS.stream().anyMatch(normalizedName::endsWith);
    }

    private UploadedFileResponse storeSingleFile(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String suffix = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) suffix = originalName.substring(dot);
        String fileId = "file_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String storedName = fileId + suffix;
        Path target = Path.of(appProperties.uploadDir()).resolve(storedName);
        byte[] content = file.getBytes();
        Files.write(target, content);
        fileRepository.upsertFileRecord(fileId, originalName, storedName, file.getContentType(), content.length);
        return new UploadedFileResponse(fileId, originalName, content.length, defaultMime(file.getContentType()));
    }

    // 将字节数组转换为 MultipartFile（匿名类实现所有抽象方法）
    private MultipartFile byteArrayToMultipartFile(byte[] content, String fileName) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return fileName;
            }

            @Override
            public String getOriginalFilename() {
                return fileName;
            }

            @Override
            public String getContentType() {
                return "text/plain";
            }

            @Override
            public boolean isEmpty() {
                return content.length == 0;
            }

            @Override
            public long getSize() {
                return content.length;
            }

            @Override
            public byte[] getBytes() throws IOException {
                return content;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(content);
            }

            @Override
            public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
                Files.write(dest.toPath(), content);
            }
        };
    }

    // 以下为原有的 buildAttachmentContext 和 defaultMime 方法，保持不变
    public String buildAttachmentContext(List<String> fileIds) {
        List<StoredFileRecord> files = getFilesInRequestedOrder(fileIds);
        if (files.isEmpty()) {
            return "无";
        }

        List<String> parts = new ArrayList<>();
        for (StoredFileRecord file : files) {
            Path path = Path.of(appProperties.uploadDir()).resolve(file.storedName());
            if (!Files.exists(path)) {
                continue;
            }
            Optional<String> parsedContent = readStoredFileContent(file);
            if (parsedContent.isPresent()) {
                String text = parsedContent.get();
                if (text.length() > 1600) {
                    text = text.substring(0, 1600) + "\n[...内容已截断...]";
                }
                parts.add("文件名: " + file.originalName() + "\n正文摘录:\n" + text.trim());
            } else {
                parts.add("文件名: " + file.originalName() + "\n正文摘录:\n[暂不支持解析该文件]");
            }
        }

        return parts.isEmpty() ? "无有效文本文件" : String.join("\n\n-----\n\n", parts);
    }

    public Optional<String> readFirstAadlContent(List<String> fileIds) {
        return getFilesInRequestedOrder(fileIds).stream()
                .filter(file -> isAadlFile(file.originalName()))
                .map(this::readStoredFileContent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public Optional<StoredFileRecord> getFileById(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        return fileRepository.getFilesByIds(List.of(fileId)).stream().findFirst();
    }

    public Optional<String> readAadlContentByFileId(String fileId) {
        return getFileById(fileId)
                .filter(file -> isAadlFile(file.originalName()))
                .flatMap(this::readStoredFileContent);
    }

    public Optional<String> getOriginalFileName(String fileId) {
        return getFileById(fileId).map(StoredFileRecord::originalName);
    }

    public List<StoredFileRecord> getFilesByIdsInOrder(List<String> fileIds) {
        return getFilesInRequestedOrder(fileIds);
    }

    public Optional<String> readFileContent(String fileId) {
        return getFileById(fileId).flatMap(this::readStoredFileContent);
    }

    public Optional<FileContentResponse> getFileContent(String fileId) {
        return getFileById(fileId).flatMap(file ->
                readStoredFileContent(file).map(content -> new FileContentResponse(
                        file.id(),
                        file.originalName(),
                        defaultMime(file.mimeType()),
                        content
                ))
        );
    }

    private List<StoredFileRecord> getFilesInRequestedOrder(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        Map<String, StoredFileRecord> byId = fileRepository.getFilesByIds(fileIds).stream()
                .collect(Collectors.toMap(StoredFileRecord::id, file -> file, (left, right) -> left, LinkedHashMap::new));
        List<StoredFileRecord> ordered = new ArrayList<>();
        for (String fileId : fileIds) {
            StoredFileRecord record = byId.get(fileId);
            if (record != null) {
                ordered.add(record);
            }
        }
        return ordered;
    }

    private Optional<String> readStoredFileContent(StoredFileRecord file) {
        Path path = Path.of(appProperties.uploadDir()).resolve(file.storedName());
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String extension = fileExtension(file.originalName());
            File storedFile = path.toFile();
            String content = switch (extension) {
                case "docx" -> new DocumentParserService().parseDocx(storedFile);
                case "doc" -> new DocumentParserService().parseDoc(storedFile);
                case "pdf" -> new PdfParserService().parsePdfToText(storedFile);
                default -> Files.readString(path, StandardCharsets.UTF_8);
            };
            return Optional.ofNullable(content);
        } catch (Exception e) {
            System.out.println("Failed to parse uploaded file: " + file.originalName() + " - " + e.getMessage());
            return Optional.empty();
        }
    }

    private String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isAadlFile(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".aadl");
    }

    private String defaultMime(String mimeType) {
        return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
    }
}
