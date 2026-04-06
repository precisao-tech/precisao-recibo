package com.precisao.recibo.service;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.RawMessage;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

@Service
public class EmailService {

    private final Session mailSession;
    private final SesClient sesClient;
    private final JavaMailSender javaMailSender;
    private final boolean sesAtivo;
    private final String emailRemetente;
    private final String nomeRemetente;

    public EmailService(
            @Value("${aws.region:us-east-2}") String awsRegion,
            @Value("${aws.access-key-id:}") String awsAccessKeyId,
            @Value("${aws.secret-access-key:}") String awsSecretAccessKey,
            @Value("${app.email.remetente}") String emailRemetente,
            @Value("${app.email.nome-remetente}") String nomeRemetente,
            JavaMailSender javaMailSender) {

        this.emailRemetente = emailRemetente;
        this.nomeRemetente = nomeRemetente;
        this.javaMailSender = javaMailSender;

        boolean credenciaisPresentes = awsAccessKeyId != null && !awsAccessKeyId.isBlank()
                && awsSecretAccessKey != null && !awsSecretAccessKey.isBlank();
        this.sesAtivo = credenciaisPresentes;

        if (sesAtivo) {
            System.out.println("Modo de envio: AWS SES API. Região: " + awsRegion);
            this.sesClient = criarSesClient(awsRegion, awsAccessKeyId, awsSecretAccessKey);
            this.mailSession = Session.getInstance(new Properties());
            System.out.println("Cliente AWS SES configurado com sucesso!");
        } else {
            System.out.println("Credenciais AWS não configuradas. Modo de envio: SMTP via JavaMailSender.");
            this.sesClient = null;
            this.mailSession = javaMailSender.createMimeMessage().getSession();
        }
        System.out.println("Email remetente: " + this.emailRemetente);
    }

    private SesClient criarSesClient(String awsRegion, String awsAccessKeyId, String awsSecretAccessKey) {
        var builder = SesClient.builder().region(Region.of(awsRegion));

        builder.credentialsProvider(
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey)
                )
        );
        System.out.println("AWS SES usando credenciais explícitas via configuração.");

        return builder.build();
    }

    public void enviarReciboEmail(
            String emailDestinatario,
            String nomeDestinatario,
            String assunto,
            byte[] pdfRecibo,
            BigDecimal valorBruto,
            String nomePrestador) throws MessagingException {
        
        enviarReciboEmailCompleto(
                emailDestinatario,
                nomeDestinatario,
                assunto,
                pdfRecibo,
                nomePrestador,
                null,
                null,
                valorBruto,
                null,
                null
        );
    }

    public void enviarReciboEmailCompleto(
            String emailDestinatario,
            String nomeDestinatario,
            String assunto,
            byte[] pdfRecibo,
            String nomePrestador,
            String cpfPrestador,
            String nomeCondominio,
            BigDecimal valorBruto,
            BigDecimal valorInss,
            BigDecimal valorLiquido) throws MessagingException {
        
        enviarReciboEmailCompleto(
                emailDestinatario,
                nomeDestinatario,
                assunto,
                pdfRecibo,
                nomePrestador,
                cpfPrestador,
                nomeCondominio,
                null, // codigoEmpreendimento
                valorBruto,
                valorInss,
                valorLiquido,
                null, // vencimento
                null, // contaContabil
                null, // descricaoPagamento
                null, // nomeBanco
                null, // agencia
                null, // digitoAgencia
                null, // conta
                null, // digitoConta
                null  // chavePix
        );
    }

    public void enviarReciboEmailCompleto(
            String emailDestinatario,
            String nomeDestinatario,
            String assunto,
            byte[] pdfRecibo,
            String nomePrestador,
            String cpfPrestador,
            String nomeCondominio,
            String codigoEmpreendimento,
            BigDecimal valorBruto,
            BigDecimal valorInss,
            BigDecimal valorLiquido,
            String vencimento,
            String contaContabil,
            String descricaoPagamento,
            String nomeBanco,
            String agencia,
            String digitoAgencia,
            String conta,
            String digitoConta,
            String chavePix) throws MessagingException {

        // Email sempre enviado para central de pagamentos
        String emailCentralPagamentos = "centraldepagamentos@precisaoadm.com.br";
        
        String nomeArquivo = gerarNomeArquivo(nomePrestador);
        String corpoEmail = construirCorpoEmailDoTemplate(
                nomeDestinatario,
                nomePrestador,
                cpfPrestador,
                nomeCondominio,
                codigoEmpreendimento,
                valorBruto,
                valorInss,
                valorLiquido,
                vencimento,
                contaContabil,
                descricaoPagamento,
                nomeBanco,
                agencia,
                digitoAgencia,
                conta,
                digitoConta,
                chavePix
        );

        try {
            System.out.println("=== ENVIANDO EMAIL PRINCIPAL ===");
            System.out.println("Destinatário: " + emailCentralPagamentos);
            System.out.println("Assunto: " + assunto);
            System.out.println("Tentando enviar email principal para central de pagamentos via AWS SES API...");
            
            // Cria mensagem MIME - email principal vai apenas para central de pagamentos (sem CC)
            MimeMessage message = criarMensagemComAnexo(
                    emailCentralPagamentos,
                    null, // Sem CC no email principal
                    assunto,
                    corpoEmail,
                    nomeArquivo,
                    pdfRecibo
            );
            
            // Verifica o destinatário antes de enviar
            jakarta.mail.Address[] destinatarios = message.getRecipients(jakarta.mail.Message.RecipientType.TO);
            if (destinatarios != null && destinatarios.length > 0) {
                System.out.println("Destinatário confirmado no MimeMessage: " + destinatarios[0].toString());
            }
            
            // Verifica BCC (remetente)
            jakarta.mail.Address[] bccAddresses = message.getRecipients(jakarta.mail.Message.RecipientType.BCC);
            if (bccAddresses != null && bccAddresses.length > 0) {
                System.out.println("BCC confirmado no MimeMessage: " + bccAddresses[0].toString());
            }
            
            // Envia via AWS SES API
            enviarViaSmtp(message);
            System.out.println("Email principal enviado com sucesso para: " + emailCentralPagamentos);
            System.out.println("=== FIM ENVIO EMAIL PRINCIPAL ===");
            
        } catch (Exception e) {
            System.err.println("Erro ao enviar email principal: " + e.getMessage());
            e.printStackTrace();
            throw new MessagingException("Erro ao enviar email: " + e.getMessage(), e);
        }
        
        // Envia email de confirmação separado para o remetente (Gmail)
        try {
            System.out.println("Enviando email de confirmação para o remetente: " + emailRemetente);
            
            String corpoConfirmacao = "Confirmação de Envio de Recibo\n\n" +
                    "Este é um email de confirmação de que o recibo foi enviado com sucesso.\n\n" +
                    "Detalhes:\n" +
                    "- Destinatário Principal: " + emailCentralPagamentos + "\n" +
                    "- Gerente Solicitante: " + nomeDestinatario + " (" + emailDestinatario + ")\n" +
                    "- Prestador: " + nomePrestador + "\n" +
                    "- Assunto: " + assunto + "\n\n" +
                    "O recibo foi enviado para o central de pagamentos e uma cópia foi enviada para o gerente solicitante.";
            
            MimeMessage messageConfirmacao = criarMensagemComAnexo(
                    emailRemetente,
                    null,
                    "Confirmação - " + assunto,
                    corpoConfirmacao,
                    nomeArquivo,
                    pdfRecibo
            );
            
            enviarViaSmtp(messageConfirmacao);
            System.out.println("Email de confirmação enviado com sucesso para: " + emailRemetente);
        } catch (Exception e) {
            System.err.println("Erro ao enviar email de confirmação para " + emailRemetente + ": " + e.getMessage());
            e.printStackTrace();
            // Não re-lança a exceção aqui, pois o email principal já foi enviado
        }
        
        // Envia email separado para o gerente (cópia)
        if (emailDestinatario != null && !emailDestinatario.isBlank() && !emailDestinatario.equals(emailCentralPagamentos)) {
            try {
                System.out.println("Tentando enviar email de cópia para o gerente: " + emailDestinatario);
                
                MimeMessage messageCopia = criarMensagemComAnexo(
                        emailDestinatario,
                        null,
                        assunto + " - Cópia",
                        "Você está recebendo uma cópia do recibo solicitado.\n\n" + corpoEmail,
                        nomeArquivo,
                        pdfRecibo
                );
                
                enviarViaSmtp(messageCopia);
                System.out.println("Email de cópia enviado com sucesso para o gerente!");
            } catch (Exception e) {
                System.err.println("Erro ao enviar cópia do email para " + emailDestinatario + ": " + e.getMessage());
                e.printStackTrace();
                // Não re-lança a exceção aqui, pois o email principal já foi enviado
            }
        }
    }

    @Async("emailExecutor")
    public void enviarReciboEmailComMultiplosAnexosAsync(
            String emailDestinatario,
            String nomeDestinatario,
            String assunto,
            java.util.Map<String, byte[]> pdfsRecibos,
            String nomePrestador,
            String cpfPrestador,
            String nomeCondominio,
            String codigoEmpreendimento,
            BigDecimal valorBruto,
            BigDecimal valorInss,
            BigDecimal valorLiquido,
            String vencimento,
            String contaContabil,
            String descricaoPagamento,
            String nomeBanco,
            String agencia,
            String digitoAgencia,
            String conta,
            String digitoConta,
            String chavePix) {
        try {
            enviarReciboEmailComMultiplosAnexos(
                    emailDestinatario,
                    nomeDestinatario,
                    assunto,
                    pdfsRecibos,
                    nomePrestador,
                    cpfPrestador,
                    nomeCondominio,
                    codigoEmpreendimento,
                    valorBruto,
                    valorInss,
                    valorLiquido,
                    vencimento,
                    contaContabil,
                    descricaoPagamento,
                    nomeBanco,
                    agencia,
                    digitoAgencia,
                    conta,
                    digitoConta,
                    chavePix
            );
        } catch (MessagingException e) {
            System.err.println("ERRO ao enviar email de forma assíncrona: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void enviarReciboEmailComMultiplosAnexos(
            String emailDestinatario,
            String nomeDestinatario,
            String assunto,
            java.util.Map<String, byte[]> pdfsRecibos,
            String nomePrestador,
            String cpfPrestador,
            String nomeCondominio,
            String codigoEmpreendimento,
            BigDecimal valorBruto,
            BigDecimal valorInss,
            BigDecimal valorLiquido,
            String vencimento,
            String contaContabil,
            String descricaoPagamento,
            String nomeBanco,
            String agencia,
            String digitoAgencia,
            String conta,
            String digitoConta,
            String chavePix) throws MessagingException {

        // Email sempre enviado para central de pagamentos
        String emailCentralPagamentos = "centraldepagamentos@precisaoadm.com.br";
        
        String corpoEmail = construirCorpoEmailDoTemplate(
                nomeDestinatario,
                nomePrestador,
                cpfPrestador,
                nomeCondominio,
                codigoEmpreendimento,
                valorBruto,
                valorInss,
                valorLiquido,
                vencimento,
                contaContabil,
                descricaoPagamento,
                nomeBanco,
                agencia,
                digitoAgencia,
                conta,
                digitoConta,
                chavePix
        );

        try {
            System.out.println("=== ENVIANDO EMAIL PRINCIPAL (MÚLTIPLOS ANEXOS) ===");
            System.out.println("Destinatário: " + emailCentralPagamentos);
            System.out.println("Assunto: " + assunto);
            System.out.println("Número de anexos: " + pdfsRecibos.size());
            System.out.println("Tentando enviar email com múltiplos anexos para central de pagamentos via AWS SES API...");
            
            // Cria mensagem MIME com múltiplos anexos - email principal vai apenas para central de pagamentos (sem CC)
            MimeMessage message = criarMensagemComMultiplosAnexos(
                    emailCentralPagamentos,
                    null, // Sem CC no email principal
                    assunto,
                    corpoEmail,
                    pdfsRecibos
            );
            
            // Verifica o destinatário antes de enviar
            jakarta.mail.Address[] destinatarios = message.getRecipients(jakarta.mail.Message.RecipientType.TO);
            if (destinatarios != null && destinatarios.length > 0) {
                System.out.println("Destinatário confirmado no MimeMessage: " + destinatarios[0].toString());
            }
            
            // Verifica BCC (remetente)
            jakarta.mail.Address[] bccAddresses = message.getRecipients(jakarta.mail.Message.RecipientType.BCC);
            if (bccAddresses != null && bccAddresses.length > 0) {
                System.out.println("BCC confirmado no MimeMessage: " + bccAddresses[0].toString());
            }
            
            // Envia via AWS SES API
            enviarViaSmtp(message);
            System.out.println("Email com múltiplos anexos enviado com sucesso para: " + emailCentralPagamentos);
            System.out.println("=== FIM ENVIO EMAIL PRINCIPAL (MÚLTIPLOS ANEXOS) ===");
            
        } catch (Exception e) {
            System.err.println("Erro ao enviar email principal: " + e.getMessage());
            e.printStackTrace();
            throw new MessagingException("Erro ao enviar email: " + e.getMessage(), e);
        }
        
        // Envia email de confirmação separado para o remetente (Gmail)
        try {
            System.out.println("Enviando email de confirmação para o remetente: " + emailRemetente);
            
            String corpoConfirmacao = "Confirmação de Envio de Recibos\n\n" +
                    "Este é um email de confirmação de que os recibos foram enviados com sucesso.\n\n" +
                    "Detalhes:\n" +
                    "- Destinatário Principal: " + emailCentralPagamentos + "\n" +
                    "- Gerente Solicitante: " + nomeDestinatario + " (" + emailDestinatario + ")\n" +
                    "- Prestador: " + nomePrestador + "\n" +
                    "- Assunto: " + assunto + "\n" +
                    "- Número de Recibos: " + pdfsRecibos.size() + "\n\n" +
                    "Os recibos foram enviados para o central de pagamentos e uma cópia foi enviada para o gerente solicitante.";
            
            MimeMessage messageConfirmacao = criarMensagemComMultiplosAnexos(
                    emailRemetente,
                    null,
                    "Confirmação - " + assunto,
                    corpoConfirmacao,
                    pdfsRecibos
            );
            
            enviarViaSmtp(messageConfirmacao);
            System.out.println("Email de confirmação enviado com sucesso para: " + emailRemetente);
        } catch (Exception e) {
            System.err.println("Erro ao enviar email de confirmação para " + emailRemetente + ": " + e.getMessage());
            e.printStackTrace();
            // Não re-lança a exceção aqui, pois o email principal já foi enviado
        }
        
        // Envia email separado para o gerente (cópia)
        if (emailDestinatario != null && !emailDestinatario.isBlank() && !emailDestinatario.equals(emailCentralPagamentos)) {
            try {
                System.out.println("Tentando enviar email de cópia para o gerente: " + emailDestinatario);
                
                MimeMessage messageCopia = criarMensagemComMultiplosAnexos(
                        emailDestinatario,
                        null,
                        assunto + " - Cópia",
                        "Você está recebendo uma cópia dos recibos solicitados.\n\n" + corpoEmail,
                        pdfsRecibos
                );
                
                enviarViaSmtp(messageCopia);
                System.out.println("Email de cópia enviado com sucesso para o gerente!");
            } catch (Exception e) {
                System.err.println("Erro ao enviar cópia do email para " + emailDestinatario + ": " + e.getMessage());
                e.printStackTrace();
                // Não re-lança a exceção aqui, pois o email principal já foi enviado
            }
        }
    }

    private MimeMessage criarMensagemComAnexo(
            String destinatario,
            String cc,
            String assunto,
            String corpoHtml,
            String nomeArquivo,
            byte[] anexo) throws MessagingException {
        
        MimeMessage message = new MimeMessage(mailSession);
        
        try {
            message.setFrom(new InternetAddress(emailRemetente, nomeRemetente, "UTF-8"));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(destinatario));
            
            if (cc != null && !cc.isBlank()) {
                message.setRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            
            // Removido BCC do remetente - pode causar problemas de reputação
            // Se precisar de confirmação, envie email separado
            
            // Adiciona Reply-To
            message.setReplyTo(new jakarta.mail.Address[] { new InternetAddress(emailRemetente) });
            
            message.setSubject(assunto, "UTF-8");
            
            // Headers para melhorar a entrega e evitar spam
            // Message-ID único para cada email
            String messageId = "<" + UUID.randomUUID().toString() + "@precisaoadm.com.br>";
            message.setHeader("Message-ID", messageId);
            
            // Headers de prioridade (removido "Precedence: bulk" que é sinal negativo)
            message.setHeader("X-Priority", "3");
            message.setHeader("Importance", "Normal");
            message.setHeader("X-Mailer", "Sistema de Recibos Precisão");
            
            // Headers adicionais para melhorar reputação
            message.setHeader("X-Auto-Response-Suppress", "All");
            message.setHeader("Auto-Submitted", "auto-generated");
            
        } catch (java.io.UnsupportedEncodingException e) {
            // Fallback sem charset se UTF-8 não for suportado
            message.setFrom(new InternetAddress(emailRemetente));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(destinatario));
            
            if (cc != null && !cc.isBlank()) {
                message.setRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            
            // Removido BCC do remetente - pode causar problemas de reputação
            // Se precisar de confirmação, envie email separado
            
            // Adiciona Reply-To
            message.setReplyTo(new jakarta.mail.Address[] { new InternetAddress(emailRemetente) });
            
            message.setSubject(assunto);
            
            // Headers para melhorar a entrega e evitar spam
            // Message-ID único para cada email
            String messageId = "<" + UUID.randomUUID().toString() + "@precisaoadm.com.br>";
            message.setHeader("Message-ID", messageId);
            
            // Headers de prioridade (removido "Precedence: bulk" que é sinal negativo)
            message.setHeader("X-Priority", "3");
            message.setHeader("Importance", "Normal");
            message.setHeader("X-Mailer", "Sistema de Recibos Precisão");
            
            // Headers adicionais para melhorar reputação
            message.setHeader("X-Auto-Response-Suppress", "All");
            // Removido Auto-Submitted pois pode ser visto como negativo por alguns filtros
            message.setHeader("Date", ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            message.setHeader("MIME-Version", "1.0");
        }
        
        // Cria estrutura multipart: alternative (text + html) + anexos
        MimeMultipart rootMultipart = new MimeMultipart("mixed");
        
        // Container para texto e HTML (alternative)
        MimeMultipart alternativeMultipart = new MimeMultipart("alternative");
        MimeBodyPart alternativePart = new MimeBodyPart();
        
        // Parte texto plano (importante para evitar spam)
        MimeBodyPart textPart = new MimeBodyPart();
        String textoPlano = converterHtmlParaTexto(corpoHtml);
        textPart.setContent(textoPlano, "text/plain; charset=UTF-8");
        alternativeMultipart.addBodyPart(textPart);
        
        // Parte HTML
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(corpoHtml, "text/html; charset=UTF-8");
        alternativeMultipart.addBodyPart(htmlPart);
        
        alternativePart.setContent(alternativeMultipart);
        rootMultipart.addBodyPart(alternativePart);
        
        // Parte anexo
        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setFileName(nomeArquivo);
        attachmentPart.setContent(anexo, "application/pdf");
        attachmentPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
        rootMultipart.addBodyPart(attachmentPart);
        
        message.setContent(rootMultipart);
        return message;
    }

    private MimeMessage criarMensagemComMultiplosAnexos(
            String destinatario,
            String cc,
            String assunto,
            String corpoHtml,
            java.util.Map<String, byte[]> anexos) throws MessagingException {
        
        MimeMessage message = new MimeMessage(mailSession);
        
        try {
            message.setFrom(new InternetAddress(emailRemetente, nomeRemetente, "UTF-8"));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(destinatario));
            
            if (cc != null && !cc.isBlank()) {
                message.setRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            
            // Removido BCC do remetente - pode causar problemas de reputação
            // Se precisar de confirmação, envie email separado
            
            // Adiciona Reply-To
            message.setReplyTo(new jakarta.mail.Address[] { new InternetAddress(emailRemetente) });
            
            message.setSubject(assunto, "UTF-8");
            
            // Headers para melhorar a entrega e evitar spam
            // Message-ID único para cada email
            String messageId = "<" + UUID.randomUUID().toString() + "@precisaoadm.com.br>";
            message.setHeader("Message-ID", messageId);
            
            // Headers de prioridade (removido "Precedence: bulk" que é sinal negativo)
            message.setHeader("X-Priority", "3");
            message.setHeader("Importance", "Normal");
            message.setHeader("X-Mailer", "Sistema de Recibos Precisão");
            
            // Headers adicionais para melhorar reputação
            message.setHeader("X-Auto-Response-Suppress", "All");
            // Removido Auto-Submitted pois pode ser visto como negativo por alguns filtros
            message.setHeader("Date", ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            message.setHeader("MIME-Version", "1.0");
            
        } catch (java.io.UnsupportedEncodingException e) {
            message.setFrom(new InternetAddress(emailRemetente));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(destinatario));
            
            if (cc != null && !cc.isBlank()) {
                message.setRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc));
            }
            
            // Removido BCC do remetente - pode causar problemas de reputação
            // Se precisar de confirmação, envie email separado
            
            // Adiciona Reply-To
            message.setReplyTo(new jakarta.mail.Address[] { new InternetAddress(emailRemetente) });
            
            message.setSubject(assunto);
            
            // Headers para melhorar a entrega e evitar spam
            // Message-ID único para cada email
            String messageId = "<" + UUID.randomUUID().toString() + "@precisaoadm.com.br>";
            message.setHeader("Message-ID", messageId);
            
            // Headers de prioridade (removido "Precedence: bulk" que é sinal negativo)
            message.setHeader("X-Priority", "3");
            message.setHeader("Importance", "Normal");
            message.setHeader("X-Mailer", "Sistema de Recibos Precisão");
            
            // Headers adicionais para melhorar reputação
            message.setHeader("X-Auto-Response-Suppress", "All");
            // Removido Auto-Submitted pois pode ser visto como negativo por alguns filtros
            message.setHeader("Date", ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            message.setHeader("MIME-Version", "1.0");
        }
        
        // Cria estrutura multipart: alternative (text + html) + anexos
        MimeMultipart rootMultipart = new MimeMultipart("mixed");
        
        // Container para texto e HTML (alternative)
        MimeMultipart alternativeMultipart = new MimeMultipart("alternative");
        MimeBodyPart alternativePart = new MimeBodyPart();
        
        // Parte texto plano (importante para evitar spam)
        MimeBodyPart textPart = new MimeBodyPart();
        String textoPlano = converterHtmlParaTexto(corpoHtml);
        textPart.setContent(textoPlano, "text/plain; charset=UTF-8");
        alternativeMultipart.addBodyPart(textPart);
        
        // Parte HTML
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(corpoHtml, "text/html; charset=UTF-8");
        alternativeMultipart.addBodyPart(htmlPart);
        
        alternativePart.setContent(alternativeMultipart);
        rootMultipart.addBodyPart(alternativePart);
        
        // Adiciona todos os anexos
        for (java.util.Map.Entry<String, byte[]> anexo : anexos.entrySet()) {
            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.setFileName(anexo.getKey());
            attachmentPart.setContent(anexo.getValue(), "application/pdf");
            attachmentPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
            rootMultipart.addBodyPart(attachmentPart);
        }
        
        message.setContent(rootMultipart);
        return message;
    }

    private void enviarViaSmtp(MimeMessage message) throws MessagingException {
        jakarta.mail.Address[] toAddresses = message.getRecipients(jakarta.mail.Message.RecipientType.TO);
        String destinatarioLog = (toAddresses != null && toAddresses.length > 0)
                ? toAddresses[0].toString() : "desconhecido";

        if (sesAtivo) {
            try {
                System.out.println("Enviando email via AWS SES API para: " + destinatarioLog);
                message.saveChanges();
                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    message.writeTo(outputStream);
                    SendRawEmailRequest request = SendRawEmailRequest.builder()
                            .rawMessage(RawMessage.builder()
                                    .data(SdkBytes.fromByteArray(outputStream.toByteArray()))
                                    .build())
                            .build();
                    sesClient.sendRawEmail(request);
                }
                System.out.println("Email enviado com sucesso via AWS SES API!");
            } catch (SesException e) {
                System.err.println("Erro AWS SES: " + e.awsErrorDetails().errorMessage());
                throw new MessagingException("Erro ao enviar via AWS SES: " + e.awsErrorDetails().errorMessage(), e);
            } catch (IOException | jakarta.mail.MessagingException e) {
                System.err.println("Erro ao preparar mensagem para AWS SES: " + e.getMessage());
                throw new MessagingException("Erro ao preparar mensagem: " + e.getMessage(), e);
            }
        } else {
            try {
                System.out.println("Enviando email via SMTP (JavaMailSender) para: " + destinatarioLog);
                javaMailSender.send(message);
                System.out.println("Email enviado com sucesso via SMTP!");
            } catch (MailException e) {
                System.err.println("Erro ao enviar via SMTP: " + e.getMessage());
                throw new MessagingException("Erro ao enviar via SMTP: " + e.getMessage(), e);
            }
        }
    }

    private String construirCorpoEmailDoTemplate(
            String nomeDestinatario,
            String nomePrestador,
            String cpfPrestador,
            String nomeCondominio,
            String codigoEmpreendimento,
            BigDecimal valorBruto,
            BigDecimal valorInss,
            BigDecimal valorLiquido,
            String vencimento,
            String contaContabil,
            String descricaoPagamento,
            String nomeBanco,
            String agencia,
            String digitoAgencia,
            String conta,
            String digitoConta,
            String chavePix) {
        
        try {
            // Carrega o novo template HTML de pagamento
            ClassPathResource resource = new ClassPathResource("templates/email-pagamento-template.html");
            String template;
            
            try (InputStream inputStream = resource.getInputStream()) {
                template = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            }
            
            // Formata os dados
            String dataAtual = LocalDate.now().format(
                    DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.of("pt", "BR"))
            );
            
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));
            
            // Extrai dígitos da conta e agência se não fornecidos separadamente
            String agenciaNumero = agencia;
            String agenciaDigito = digitoAgencia;
            if (agencia != null && agencia.contains("-")) {
                String[] partesAgencia = agencia.split("-");
                agenciaNumero = partesAgencia[0];
                if (partesAgencia.length > 1) {
                    agenciaDigito = partesAgencia[1];
                }
            }
            
            String contaNumero = conta;
            String contaDigito = digitoConta;
            if (conta != null && conta.contains("-")) {
                String[] partesConta = conta.split("-");
                contaNumero = partesConta[0];
                if (partesConta.length > 1) {
                    contaDigito = partesConta[1];
                }
            }
            
            // Substitui os placeholders do novo template
            template = template.replace("{{VALOR_PAGAMENTO}}", 
                    valorLiquido != null ? currencyFormat.format(valorLiquido) : 
                    (valorBruto != null ? currencyFormat.format(valorBruto) : "R$ 0,00"));
            
            template = template.replace("{{CODIGO_EMPREENDIMENTO}}", 
                    codigoEmpreendimento != null ? codigoEmpreendimento : "Não informado");
            
            template = template.replace("{{NOME_EMPREENDIMENTO}}", 
                    nomeCondominio != null ? nomeCondominio : "Não informado");
            
            template = template.replace("{{VENCIMENTO}}", 
                    vencimento != null ? vencimento : dataAtual);
            
            template = template.replace("{{CONTA_CONTABIL}}", 
                    contaContabil != null ? contaContabil : "Não informado");
            
            template = template.replace("{{DESCRICAO_PAGAMENTO}}", 
                    descricaoPagamento != null ? descricaoPagamento : 
                    (nomePrestador != null ? "Pagamento de serviços - " + nomePrestador : "Pagamento de serviços"));
            
            template = template.replace("{{DOCUMENTO_FORNECEDOR}}", 
                    cpfPrestador != null ? formatarCPF(cpfPrestador) : "Não informado");
            
            template = template.replace("{{NOME_FORNECEDOR}}", 
                    nomePrestador != null ? nomePrestador : "Não informado");
            
            template = template.replace("{{DOCUMENTO_FAVORECIDO}}", 
                    cpfPrestador != null ? formatarCPF(cpfPrestador) : "Não informado");
            
            template = template.replace("{{NOME_FAVORECIDO}}", 
                    nomePrestador != null ? nomePrestador : "Não informado");
            
            template = template.replace("{{NUMERO_BANCO}}", 
                    nomeBanco != null ? nomeBanco : "Não informado");
            
            template = template.replace("{{AGENCIA}}", 
                    agenciaNumero != null ? agenciaNumero : "Não informado");
            
            template = template.replace("{{DIGITO_AGENCIA}}", 
                    agenciaDigito != null && !agenciaDigito.isBlank() ? agenciaDigito : "Não informado");
            
            template = template.replace("{{NUMERO_CONTA}}", 
                    contaNumero != null ? contaNumero : "Não informado");
            
            template = template.replace("{{DIGITO_CONTA}}", 
                    contaDigito != null && !contaDigito.isBlank() ? contaDigito : "Não informado");
            
            template = template.replace("{{CHAVE_PIX}}", 
                    chavePix != null ? chavePix : "Não informado");
            
            return template;
            
        } catch (IOException e) {
            System.err.println("Erro ao carregar template de email: " + e.getMessage());
            e.printStackTrace();
            // Fallback para o template inline se houver erro
            return construirCorpoEmail(nomeDestinatario, valorBruto, nomePrestador);
        }
    }

    private String formatarCPF(String cpf) {
        if (cpf == null) {
            return "";
        }
        // Remove formatação se já existir
        String cpfLimpo = cpf.replaceAll("[^0-9]", "");
        if (cpfLimpo.length() != 11) {
            return cpf;
        }
        return String.format("%s.%s.%s-%s",
                cpfLimpo.substring(0, 3),
                cpfLimpo.substring(3, 6),
                cpfLimpo.substring(6, 9),
                cpfLimpo.substring(9, 11));
    }

    private String construirCorpoEmail(String nomeDestinatario, BigDecimal valorBruto, String nomePrestador) {
        String saudacao = nomeDestinatario != null && !nomeDestinatario.isBlank()
                ? "Prezado(a) " + nomeDestinatario
                : "Prezado(a)";

        String dataAtual = LocalDate.now().format(
                DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.of("pt", "BR"))
        );

        String valorFormatado = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"))
                .format(valorBruto);

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            line-height: 1.6;
                            color: #333;
                        }
                        .container {
                            max-width: 600px;
                            margin: 0 auto;
                            padding: 20px;
                        }
                        .header {
                            background-color: #0066cc;
                            color: white;
                            padding: 20px;
                            text-align: center;
                            border-radius: 5px 5px 0 0;
                        }
                        .content {
                            background-color: #f9f9f9;
                            padding: 30px;
                            border: 1px solid #ddd;
                        }
                        .info-box {
                            background-color: #e8f4f8;
                            border-left: 4px solid #0066cc;
                            padding: 15px;
                            margin: 20px 0;
                        }
                        .footer {
                            text-align: center;
                            padding: 20px;
                            font-size: 12px;
                            color: #666;
                            border-top: 1px solid #ddd;
                        }
                        .destaque {
                            font-weight: bold;
                            color: #0066cc;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h2>Recibo Eletronico - Pró-Labore</h2>
                        </div>
                        <div class="content">
                            <p>%s,</p>
                            
                            <p>Segue em anexo o recibo eletronico de pró-labore.</p>
                            
                            <div class="info-box">
                                <p><strong>Prestador:</strong> %s</p>
                                <p><strong>Valor:</strong> <span class="destaque">%s</span></p>
                                <p><strong>Data de Emissão:</strong> %s</p>
                            </div>
                            
                            <p>O documento está anexado a este e-mail em formato PDF.</p>
                            
                            <p>Este é um e-mail automático. Por favor, não responda a esta mensagem.</p>
                            
                            <p>Atenciosamente,<br>
                            <strong>Sistema de Recibos - Precisão</strong></p>
                        </div>
                        <div class="footer">
                            <p>© %d Sistema de Recibos - Precisão. Todos os direitos reservados.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(
                saudacao,
                nomePrestador != null ? nomePrestador : "Não informado",
                valorFormatado,
                dataAtual,
                LocalDate.now().getYear()
        );
    }

    private String converterHtmlParaTexto(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        
        // Remove tags HTML básicas e converte para texto
        String texto = html
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<p[^>]*>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("<div[^>]*>", "\n")
                .replaceAll("</div>", "\n")
                .replaceAll("<h[1-6][^>]*>", "\n")
                .replaceAll("</h[1-6]>", "\n")
                .replaceAll("<li[^>]*>", "\n- ")
                .replaceAll("</li>", "")
                .replaceAll("<ul[^>]*>", "\n")
                .replaceAll("</ul>", "\n")
                .replaceAll("<ol[^>]*>", "\n")
                .replaceAll("</ol>", "\n")
                .replaceAll("<strong[^>]*>", "")
                .replaceAll("</strong>", "")
                .replaceAll("<b[^>]*>", "")
                .replaceAll("</b>", "")
                .replaceAll("<em[^>]*>", "")
                .replaceAll("</em>", "")
                .replaceAll("<i[^>]*>", "")
                .replaceAll("</i>", "")
                .replaceAll("<[^>]+>", "") // Remove todas as outras tags
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n") // Remove múltiplas quebras de linha
                .trim();
        
        return texto;
    }

    private String gerarNomeArquivo(String nomePrestador) {
        String dataAtual = LocalDate.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd", Locale.of("pt", "BR"))
        );

        String nomeArquivoBase = "Recibo_ProLabore";
        
        if (nomePrestador != null && !nomePrestador.isBlank()) {
            String nomeSanitizado = nomePrestador
                    .replaceAll("[^a-zA-Z0-9\\s]", "")
                    .replaceAll("\\s+", "_")
                    .trim();
            if (!nomeSanitizado.isEmpty()) {
                nomeArquivoBase += "_" + nomeSanitizado;
            }
        }
        
        return nomeArquivoBase + "_" + dataAtual + ".pdf";
    }
}
