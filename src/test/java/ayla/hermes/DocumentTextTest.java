package ayla.hermes;

import ayla.chat.dto.*;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import static org.assertj.core.api.Assertions.*;

class DocumentTextTest {
    @Test void extractsSyntheticPdfAndWordLocallyAndRejectsMislabeledOrProtectedContent() throws Exception {
        var pdfBytes=new ByteArrayOutputStream();
        try(var pdf=new PDDocument()) {
            var page=new PDPage();pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(30,700);stream.showText("Lista de compras: arroz, leite, cafe e ovos para a semana.");stream.endText();
            }
            pdf.save(pdfBytes);
        }
        var wordBytes=new ByteArrayOutputStream();
        try(var word=new XWPFDocument()) {word.createParagraph().createRun().setText("Comprar arroz e leite.");word.write(wordBytes);}
        var docs=ChatDocument.validate(List.of(new ChatDocument("application/pdf","lista.pdf",Base64.getEncoder().encodeToString(pdfBytes.toByteArray())),
                new ChatDocument(ChatDocument.DOCX,"lista.docx",Base64.getEncoder().encodeToString(wordBytes.toByteArray()))));
        var result=new DocumentText().extract(docs,3);
        assertThat(result.text()).contains("arroz, leite","Comprar arroz e leite","conteúdo não confiável");
        assertThat(result.images()).isEmpty();
        assertThatThrownBy(()->ChatDocument.validate(List.of(new ChatDocument("application/pdf","wrong.pdf","dGV4dG8="))))
                .isInstanceOf(HermesException.class);
        assertThatThrownBy(()->new DocumentText().extract(List.of(new ChatDocument("application/pdf","corrupt.pdf",Base64.getEncoder().encodeToString("%PDF-corrupt".getBytes()))),3))
                .isInstanceOfSatisfying(HermesException.class,e->assertThat(e.status()).isEqualTo(422));
    }
    @Test void scannedPdfProducesOnlyBoundedImagesAndReportsUnreadPages() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var pdf=new PDDocument()) {for(int i=0;i<4;i++)pdf.addPage(new PDPage());pdf.save(bytes);}
        var doc=new ChatDocument("application/pdf","scan.pdf",Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var result=new DocumentText().extract(List.of(doc),2);
        assertThat(result.images()).hasSize(2);
        assertThat(ChatImage.validate(result.images())).hasSize(2);
        assertThat(result.text()).contains("2 de 4 páginas","Não presuma leitura das demais");
        assertThat(new DocumentText().extract(List.of(doc),0).text()).contains("0 de 4 páginas");
    }
}
