package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Controller
public class FileUploadController {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Autowired
    private QuestionService questionService;

    @PostMapping("/upload")
    public ResponseEntity<String> handleFileUpload(@RequestParam("file") MultipartFile file) {
        try {
            String filename = StringUtils.cleanPath(file.getOriginalFilename());
            String outputCsvFile = "generated_questions.csv";
            File targetFile = new File(uploadDir, filename);

            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }

            Files.copy(file.getInputStream(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            String inputCsvFile = targetFile.getAbsolutePath();

            questionService.processQuestions(inputCsvFile, outputCsvFile);

            return ResponseEntity.ok("File uploaded and processed successfully: " + filename);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to upload and process the file: " + e.getMessage());
        }
    }
}
