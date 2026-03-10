package kz.courier.notification.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine emailTemplateEngine;

    @Value("${notification.mail.from}")
    private String from;

    /**
     * Sends an HTML email rendered from a Thymeleaf template.
     *
     * @param to           recipient address
     * @param subject      email subject line
     * @param templateName template file name without suffix (e.g. "email-verification")
     * @param variables    variables injected into the Thymeleaf context
     */
    public void sendHtml(String to, String subject, String templateName, Map<String, Object> variables) {
        try {
            Context ctx = new Context();
            ctx.setVariables(variables);
            String html = emailTemplateEngine.process(templateName, ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Email '{}' sent to {}", subject, to);
        } catch (Exception ex) {
            log.error("Failed to send email '{}' to {}: {}", subject, to, ex.getMessage(), ex);
            throw new RuntimeException("Email delivery failed", ex);
        }
    }
}