package com.example.jobpuzzle.document.service;

import com.example.jobpuzzle.document.dto.DocumentResponse;
import com.example.jobpuzzle.document.dto.DocumentUploadRequest;
import com.example.jobpuzzle.document.entity.UserDocument;
import com.example.jobpuzzle.document.entity.UserDocumentSourceType;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.repository.DocumentExtractionRepository;
import com.example.jobpuzzle.document.repository.UserDocumentRepository;
import com.example.jobpuzzle.document.storage.FileStorage;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private UserDocumentRepository userDocumentRepository;
    @Mock
    private DocumentExtractionRepository documentExtractionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorage fileStorage;

    @InjectMocks
    private DocumentService documentService;

    @Test
    @DisplayName("PDF 1개는 정상 업로드된다")
    void uploadDocument_singlePdf_success() {
        stubUserAndStorage();
        MockMultipartFile pdf = new MockMultipartFile("files", "posting.pdf", "application/pdf", "dummy".getBytes());

        DocumentResponse response = documentService.uploadDocument(1L, uploadRequest(List.of(pdf)));

        assertThat(response.getSourceType()).isEqualTo(UserDocumentSourceType.PDF);
        assertThat(response.getFiles()).hasSize(1);
        assertThat(response.getFiles().get(0).fileName()).isEqualTo("posting.pdf");
        assertThat(response.getFiles().get(0).pageOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미지 여러 장은 업로드 순서대로 pageOrder가 매겨져 저장된다")
    void uploadDocument_multipleImages_success() {
        stubUserAndStorage();
        MockMultipartFile image1 = new MockMultipartFile("files", "cap1.png", "image/png", "a".getBytes());
        MockMultipartFile image2 = new MockMultipartFile("files", "cap2.jpg", "image/jpeg", "b".getBytes());

        DocumentResponse response = documentService.uploadDocument(1L, uploadRequest(List.of(image1, image2)));

        assertThat(response.getSourceType()).isEqualTo(UserDocumentSourceType.IMAGE);
        assertThat(response.getFiles()).hasSize(2);
        assertThat(response.getFiles().get(0).fileName()).isEqualTo("cap1.png");
        assertThat(response.getFiles().get(0).pageOrder()).isEqualTo(1);
        assertThat(response.getFiles().get(1).fileName()).isEqualTo("cap2.jpg");
        assertThat(response.getFiles().get(1).pageOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("PDF와 이미지를 함께 업로드하면 거부된다")
    void uploadDocument_mixedPdfAndImage_throwsMixedFileType() {
        MockMultipartFile pdf = new MockMultipartFile("files", "posting.pdf", "application/pdf", "dummy".getBytes());
        MockMultipartFile image = new MockMultipartFile("files", "cap1.png", "image/png", "a".getBytes());

        assertThatThrownBy(() -> documentService.uploadDocument(1L, uploadRequest(List.of(pdf, image))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MIXED_FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("PDF는 한 번에 2개 이상 업로드할 수 없다")
    void uploadDocument_multiplePdf_throwsPdfMultipleFilesNotAllowed() {
        MockMultipartFile pdf1 = new MockMultipartFile("files", "posting1.pdf", "application/pdf", "dummy".getBytes());
        MockMultipartFile pdf2 = new MockMultipartFile("files", "posting2.pdf", "application/pdf", "dummy".getBytes());

        assertThatThrownBy(() -> documentService.uploadDocument(1L, uploadRequest(List.of(pdf1, pdf2))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PDF_MULTIPLE_FILES_NOT_ALLOWED);
    }

    @Test
    @DisplayName("이미지가 21장이면 최대 장수를 초과해 거부된다")
    void uploadDocument_tooManyImages_throwsImageCountExceeded() {
        List<MultipartFile> images = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            images.add(new MockMultipartFile("files", "cap" + i + ".png", "image/png", "a".getBytes()));
        }

        assertThatThrownBy(() -> documentService.uploadDocument(1L, uploadRequest(images)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_COUNT_EXCEEDED);
    }

    @Test
    @DisplayName("지원하지 않는 확장자는 기존과 동일하게 거부된다")
    void uploadDocument_invalidExtension_throwsInvalidFileType() {
        MockMultipartFile file = new MockMultipartFile("files", "posting.hwp", "application/octet-stream", "a".getBytes());

        assertThatThrownBy(() -> documentService.uploadDocument(1L, uploadRequest(List.of(file))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FILE_TYPE);
    }

    private void stubUserAndStorage() {
        when(userRepository.getReferenceById(1L)).thenReturn(User.builder().build());
        when(fileStorage.store(any(), any())).thenReturn("stored/path");
    }

    private DocumentUploadRequest uploadRequest(List<MultipartFile> files) {
        return new DocumentUploadRequest(UserDocumentType.JOB_POSTING, files, "테스트 자료", false);
    }
}