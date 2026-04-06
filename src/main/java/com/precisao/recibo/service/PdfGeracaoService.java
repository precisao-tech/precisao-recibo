package com.precisao.recibo.service;

// Imports do ZXing serão carregados via reflection para evitar erro se não estiverem disponíveis
import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.precisao.recibo.dto.ReciboRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class PdfGeracaoService {

    private static final String TEMPLATE_PATH = "templates/PRO-LABORE-RECIBO.pdf";
    private static final String HTML_TEMPLATE_PATH = "templates/recibo-prolabore-formato-tabular.html";
    private static final String LOGO_PATH = "templates/precisão logo.png";

    /** Título exibido no PDF (definido no Java para não depender só do HTML no classpath). */
    private static final String PDF_TITULO_MAIUSCULO = "RECIBO ELETRONICO";
    private static final String PDF_TITULO_DOCUMENTO = "Recibo Eletronico";

    private final ValorExtensoService valorExtensoService;
    private final CalculoService calculoService;
    private final HtmlToPdfService htmlToPdfService;
    private final String backendUrl;

    public PdfGeracaoService(
            ValorExtensoService valorExtensoService, 
            CalculoService calculoService, 
            HtmlToPdfService htmlToPdfService,
            @Value("${app.backend.url:http://localhost:8080}") String backendUrl) {
        this.valorExtensoService = valorExtensoService;
        this.calculoService = calculoService;
        this.htmlToPdfService = htmlToPdfService;
        this.backendUrl = backendUrl;
    }

    public byte[] gerarReciboPDF(ReciboRequest request) throws IOException {
        // Novo método usando HTML template
        return gerarReciboPDFDeHTML(request, null, null);
    }

    public byte[] gerarReciboPDF(ReciboRequest request, String nomeGerente, String ipCliente) throws IOException {
        // Novo método usando HTML template com QR Code
        return gerarReciboPDFDeHTML(request, nomeGerente, ipCliente, null, null);
    }

    public byte[] gerarReciboPDF(ReciboRequest request, String nomeGerente, String ipCliente, String dataVencimento, Integer numeroParcela) throws IOException {
        // Método para gerar recibo com data específica e número da parcela
        return gerarReciboPDFDeHTML(request, nomeGerente, ipCliente, dataVencimento, numeroParcela);
    }

    public byte[] gerarReciboPDFDeHTML(ReciboRequest request, String nomeGerente, String ipCliente) throws IOException {
        return gerarReciboPDFDeHTML(request, nomeGerente, ipCliente, null, null);
    }

    public byte[] gerarReciboPDFDeHTML(ReciboRequest request, String nomeGerente, String ipCliente, String dataVencimento, Integer numeroParcela) throws IOException {
        BigDecimal valorBruto = request.valorBruto();
        BigDecimal valorINSS = calcularINSSComTipo(request.valorBruto(), request.tipoImposto());
        BigDecimal valorLiquido = calculoService.calcularValorLiquido(valorBruto, valorINSS);
        String valorBrutoPorExtenso = valorExtensoService.converter(valorBruto);
        String valorLiquidoPorExtenso = valorExtensoService.converter(valorLiquido);

        Map<String, String> dados = construirDadosParaHTML(request, valorBruto, valorINSS, valorLiquido, valorBrutoPorExtenso, valorLiquidoPorExtenso, nomeGerente, ipCliente, dataVencimento, numeroParcela);
        
        return htmlToPdfService.gerarPdfDeHtml(HTML_TEMPLATE_PATH, dados);
    }

    @Deprecated
    public byte[] gerarReciboPDFAntigo(ReciboRequest request) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);

        try (InputStream templateStream = resource.getInputStream()) {
            byte[] templateBytes = templateStream.readAllBytes();

            BigDecimal valorBruto = request.valorBruto();
            BigDecimal valorINSS = calcularINSSComTipo(request.valorBruto(), request.tipoImposto());
            BigDecimal valorLiquido = calculoService.calcularValorLiquido(valorBruto, valorINSS);
            String valorPorExtenso = valorExtensoService.converter(valorBruto);

            Map<String, String> valores = construirMapaDeValores(request, valorBruto, valorINSS, valorLiquido, valorPorExtenso);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            try (PdfDocument pdfDocument = new PdfDocument(
                    new PdfReader(new ByteArrayInputStream(templateBytes)),
                    new PdfWriter(outputStream)
            )) {
                PdfAcroForm acroForm = PdfAcroForm.getAcroForm(pdfDocument, true);
                Map<String, PdfFormField> campos = acroForm.getAllFormFields();

                // Mapeamento dos campos do PDF para os valores
                Map<String, String> mapeamentoCampos = criarMapeamentoCampos(valores);

                campos.forEach((nomeCampo, campo) -> {
                    String valor = mapeamentoCampos.get(nomeCampo);
                    if (valor != null) {
                        campo.setValue(valor);
                        System.out.printf("[PDF] Campo preenchido: %s = %s%n", nomeCampo, valor);
                    } else {
                        System.out.printf("[PDF] Campo sem mapeamento: %s%n", nomeCampo);
                    }
                });

                acroForm.flattenFields();
            }

            return outputStream.toByteArray();
        }
    }

    private Map<String, String> criarMapeamentoCampos(Map<String, String> valores) {
        Map<String, String> mapeamento = new HashMap<>();
        
        // Baseado na estrutura do PDF mostrada:
        // Linha 1: PRO-LABORE RECIBO | Mês de referência
        // Linha 2: Condomínio | CNPJ
        // Linha 3: Recebi da Empresa acima identificada
        // Linha 4: Dados Bancários (CPF, Banco, Agência, Conta, Chave pix) | Especificação (Descontos: INSS, Valor Líquido)
        
        mapeamento.put("textarea_1hoqn", valores.get("condominio"));        // Condomínio
        mapeamento.put("textarea_2ywas", valores.get("cnpj"));              // CNPJ
        mapeamento.put("textarea_3ccnr", valores.get("valorbruto"));        // Valor recebido
        mapeamento.put("textarea_4nu", valores.get("cpf"));                 // CPF
        mapeamento.put("textarea_5gvf", valores.get("banco"));              // Banco
        mapeamento.put("textarea_6zzrb", valores.get("agencia"));           // Agência
        mapeamento.put("textarea_7uhfa", valores.get("conta"));             // Conta
        mapeamento.put("textarea_8pofg", valores.get("pix"));               // Chave pix
        mapeamento.put("textarea_9zjdj", valores.get("descricao"));         // Especificação/Descrição
        mapeamento.put("textarea_10dvxp", valores.get("inss"));             // INSS
        mapeamento.put("textarea_11vojl", valores.get("valorliquido"));     // Valor Líquido
        mapeamento.put("textarea_12pmv", valores.get("mesreferencia"));     // Mês de referência
        
        return mapeamento;
    }

    private Map<String, String> construirMapaDeValores(ReciboRequest request,
                                                       BigDecimal valorBruto,
                                                       BigDecimal valorINSS,
                                                       BigDecimal valorLiquido,
                                                       String valorPorExtenso) {
        Map<String, String> valores = new HashMap<>();
        
        String mesReferencia = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM/yyyy", Locale.of("pt", "BR")));

        // Condomínio - Código + Nome
        String condominioCompleto = request.codigoEmpreendimento() != null && !request.codigoEmpreendimento().isBlank()
                ? request.codigoEmpreendimento() + " - " + request.condominio()
                : request.condominio();
        valores.put("condominio", condominioCompleto);
        valores.put("cnpj", formatarCNPJ(request.cnpjCondominio()));
        valores.put("valorbruto", formatarMoeda(valorBruto));
        valores.put("extenso", valorPorExtenso);
        valores.put("cpf", formatarCPF(request.cpf()));
        valores.put("banco", request.nomeBanco() != null ? request.nomeBanco() : request.codigoBanco());
        valores.put("agencia", request.agencia());
        valores.put("conta", request.conta());
        valores.put("pix", formatarChavePixCompleta(request.tipoChavePix(), request.chavePix()));
        valores.put("descricao", request.descricaoServico());
        valores.put("inss", formatarMoeda(valorINSS));
        valores.put("valorliquido", formatarMoeda(valorLiquido));
        valores.put("prestador", request.nomePrestador());
        valores.put("pis", formatarPIS(request.pis()));
        valores.put("mesreferencia", mesReferencia);

        return valores;
    }


    private String formatarMoeda(BigDecimal valor) {
        if (valor == null) {
            return "";
        }
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));
        return currencyFormat.format(valor);
    }

    private String formatarCNPJ(String cnpj) {
        if (cnpj == null || cnpj.length() != 14) {
            return cnpj == null ? "" : cnpj;
        }
        return String.format("%s.%s.%s/%s-%s",
                cnpj.substring(0, 2),
                cnpj.substring(2, 5),
                cnpj.substring(5, 8),
                cnpj.substring(8, 12),
                cnpj.substring(12, 14));
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

    private String formatarPIS(String pis) {
        if (pis == null || pis.isBlank()) {
            return "";
        }
        // Remove formatação se já existir
        String pisLimpo = pis.replaceAll("[^0-9]", "");
        if (pisLimpo.length() != 11) {
            return pis;
        }
        return String.format("%s.%s.%s-%s",
                pisLimpo.substring(0, 3),
                pisLimpo.substring(3, 6),
                pisLimpo.substring(6, 9),
                pisLimpo.substring(9, 11));
    }

    private String formatarChavePixCompleta(String tipo, String chave) {
        if (chave == null || chave.isBlank()) {
            return "";
        }
        if (tipo == null || tipo.isBlank()) {
            return chave; // Se não tiver tipo, retorna a chave sem formatação
        }
        String chaveFormatada = chave;
        if ("cpf".equalsIgnoreCase(tipo)) {
            // Remove formatação se já existir e formata como CPF
            chaveFormatada = formatarCPF(chave);
        } else if ("celular".equalsIgnoreCase(tipo)) {
            // Remove formatação se já existir
            String celularLimpo = chave.replaceAll("[^0-9]", "");
            if (celularLimpo.length() == 11) {
                chaveFormatada = String.format("(%s) %s-%s",
                        celularLimpo.substring(0, 2),
                        celularLimpo.substring(2, 7),
                        celularLimpo.substring(7, 11));
            }
        }
        
        // Retorna apenas o valor formatado, sem prefixo de tipo
        return chaveFormatada;
    }

    private Map<String, String> construirDadosParaHTML(ReciboRequest request,
                                                        BigDecimal valorBruto,
                                                        BigDecimal valorINSS,
                                                        BigDecimal valorLiquido,
                                                        String valorBrutoPorExtenso,
                                                        String valorLiquidoPorExtenso,
                                                        String nomeGerente,
                                                        String ipCliente,
                                                        String dataVencimento,
                                                        Integer numeroParcela) {
        Map<String, String> dados = new HashMap<>();

        dados.put("PDF_TITULO_MAIUSCULO", PDF_TITULO_MAIUSCULO);
        dados.put("PDF_TITULO_DOCUMENTO", PDF_TITULO_DOCUMENTO);

        // Data atual formatada (dd/MM/yyyy) - usada como fallback
        // Usa fuso horário do Brasil para garantir data/hora corretas
        java.time.ZoneId zonaBrasil = java.time.ZoneId.of("America/Sao_Paulo");
        String dataAtual = java.time.LocalDate.now(zonaBrasil).format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.of("pt", "BR"))
        );

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));
        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.of("pt", "BR"));
        numberFormat.setMinimumFractionDigits(2);
        numberFormat.setMaximumFractionDigits(2);

        // Logo
        String logoBase64 = htmlToPdfService.converterImagemParaBase64(LOGO_PATH);
        dados.put("LOGO_BASE64", logoBase64);
        dados.put("LOGO_STYLE", logoBase64.isEmpty() ? " hidden" : "");

        // Cabeçalho
        dados.put("DATA_EMISSAO", dataAtual);
        dados.put("LOCAL_EMISSAO", "Brasil");
        dados.put("LOCALIDADE", "Brasil");

        // Condomínio - Código + Nome
        String condominioCompleto = request.codigoEmpreendimento() != null && !request.codigoEmpreendimento().isBlank()
                ? request.codigoEmpreendimento() + " - " + request.condominio()
                : request.condominio();
        dados.put("NOME_CONDOMINIO", condominioCompleto);
        dados.put("CNPJ_CONDOMINIO", formatarCNPJ(request.cnpjCondominio()));
        dados.put("GRUPO_DE_SALDO", request.grupoDeSaldo() != null && !request.grupoDeSaldo().isBlank() ? request.grupoDeSaldo() : "");
        dados.put("CONTA_GRUPO_DE_SALDO", request.contaGrupoDeSaldo() != null && !request.contaGrupoDeSaldo().isBlank() ? request.contaGrupoDeSaldo() : "");

        // Valores - Formatados com símbolo
        dados.put("VALOR_BRUTO", currencyFormat.format(valorBruto));
        dados.put("VALOR_INSS", currencyFormat.format(valorINSS));
        dados.put("VALOR_LIQUIDO", currencyFormat.format(valorLiquido));
        dados.put("VALOR_BRUTO_POR_EXTENSO", valorBrutoPorExtenso);
        dados.put("VALOR_LIQUIDO_POR_EXTENSO", valorLiquidoPorExtenso);
        
        // Formato: R$ 756,00 (setecentos e cinquenta e seis reais)
        String valorLiquidoFormatado = currencyFormat.format(valorLiquido) + " (" + valorLiquidoPorExtenso + ")";
        dados.put("VALOR_LIQUIDO_FORMATADO", valorLiquidoFormatado);

        // Valores - Numéricos (sem símbolo R$, apenas números)
        dados.put("VALOR_BRUTO_NUMERICO", numberFormat.format(valorBruto));
        dados.put("VALOR_INSS_NUMERICO", numberFormat.format(valorINSS));
        dados.put("VALOR_LIQUIDO_NUMERICO", numberFormat.format(valorLiquido));

        // Prestador
        dados.put("NOME_PRESTADOR", request.nomePrestador());
        dados.put("CPF_PRESTADOR", formatarCPF(request.cpf()));
        dados.put("PIS", formatarPIS(request.pis()));

        // Dados Bancários
        dados.put("CODIGO_BANCO", request.codigoBanco() != null ? request.codigoBanco() : "");
        dados.put("NOME_BANCO", request.nomeBanco() != null ? request.nomeBanco() : "");
        dados.put("AGENCIA", request.agencia() != null ? request.agencia() : "");
        dados.put("CONTA", request.conta() != null ? request.conta() : "");
        dados.put("CHAVE_PIX", formatarChavePixCompleta(request.tipoChavePix(), request.chavePix()));

        // Especificação
        dados.put("DESCRICAO_SERVICO", request.descricaoServico() != null ? request.descricaoServico() : "");
        dados.put("TIPO_SERVICO_PRESTADO", request.descricaoServico() != null ? request.descricaoServico() : "");

        // Retenção por conta do condomínio - sempre mostra, com "Sim" ou "Não"
        String retencaoValor = (request.retencao() != null && request.retencao()) ? "Sim" : "Não";
        dados.put("RETENCAO_VALOR", retencaoValor);

        // Parcelas (obrigatório)
        String parcelas = request.parcelas() != null && !request.parcelas().isBlank() ? request.parcelas() : "1";
        // Se houver número da parcela específico, exibe "X de Y" (ex: "1 de 3")
        String parcelasExibicao = numeroParcela != null 
                ? numeroParcela + " de " + parcelas 
                : parcelas;
        dados.put("PARCELAS", parcelasExibicao);
        dados.put("PARCELAS_DISPLAY", ""); // Sempre exibe já que é obrigatório

        // Data de Vencimento - usa dataVencimento se fornecida, senão usa a do request, senão data atual
        String dataVencimentoFormatada;
        if (dataVencimento != null && !dataVencimento.isBlank()) {
            dataVencimentoFormatada = formatarDataISO(dataVencimento);
        } else if (request.data() != null && !request.data().isBlank()) {
            dataVencimentoFormatada = formatarDataISO(request.data());
        } else {
            dataVencimentoFormatada = dataAtual;
        }
        dados.put("DATA_VENCIMENTO", dataVencimentoFormatada);

        // Data de Emissão - sempre usa a data atual (now) para o campo Data do recibo
        dados.put("DATA_EMISSAO", dataAtual);

        // QR Code com informações do gerente - formato URL com dados
        String qrCodeBase64 = "";
        if (nomeGerente != null && !nomeGerente.isBlank()) {
            // Cria uma URL com os dados codificados que pode ser aberta no navegador
            // Formato: http://localhost:8080/recibos/qr-info?gerente=NOME&data=DATA&hora=HORA
            try {
                System.out.println("Gerando QR Code. Backend URL: " + backendUrl);
                String nomeGerenteEncoded = java.net.URLEncoder.encode(nomeGerente, "UTF-8");
                String dataEncoded = java.net.URLEncoder.encode(dataAtual, "UTF-8");
                // Usa o mesmo fuso horário do Brasil já definido acima
                String horaEncoded = java.net.URLEncoder.encode(
                    java.time.LocalTime.now(zonaBrasil).format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.of("pt", "BR"))
                    ), "UTF-8");
                
                // Cria URL que pode ser aberta no navegador (usa URL configurada ou localhost)
                String baseUrl = backendUrl.endsWith("/") ? backendUrl.substring(0, backendUrl.length() - 1) : backendUrl;
                String qrCodeData = String.format(
                    "%s/recibos/qr-info?gerente=%s&data=%s&hora=%s",
                    baseUrl,
                    nomeGerenteEncoded,
                    dataEncoded,
                    horaEncoded
                );
                
                qrCodeBase64 = gerarQRCodeBase64(qrCodeData, 200, 200);
                System.out.println("QR Code gerado com sucesso");
            } catch (java.io.UnsupportedEncodingException e) {
                System.err.println("Erro ao codificar URL do QR Code: " + e.getMessage());
                // Usa o mesmo fuso horário do Brasil já definido acima
                String qrCodeData = String.format(
                    "{\"tipo\":\"recibo-prolabore\",\"gerente\":\"%s\",\"data\":\"%s\",\"hora\":\"%s\"}",
                    nomeGerente.replace("\"", "\\\""),
                    dataAtual,
                    java.time.LocalTime.now(zonaBrasil).format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.of("pt", "BR"))
                    )
                );
                qrCodeBase64 = gerarQRCodeBase64(qrCodeData, 200, 200);
                System.out.println("QR Code gerado com sucesso (JSON)");
            } catch (Exception e) {
                System.err.println("Erro ao gerar QR Code: " + e.getMessage());
                e.printStackTrace();
                // Continua sem QR Code se houver erro
                qrCodeBase64 = "";
            }
        }
        dados.put("QR_CODE_BASE64", qrCodeBase64);
        dados.put("QR_CODE_STYLE", qrCodeBase64.isEmpty() ? " hidden" : "");

        return dados;
    }

    private String gerarQRCodeBase64(String texto, int largura, int altura) {
        try {
            // Verifica se as classes do ZXing estão disponíveis
            Class<?> encodeHintTypeClass = Class.forName("com.google.zxing.EncodeHintType");
            Class<?> errorCorrectionLevelClass = Class.forName("com.google.zxing.qrcode.decoder.ErrorCorrectionLevel");
            Class<?> qrCodeWriterClass = Class.forName("com.google.zxing.qrcode.QRCodeWriter");
            Class<?> barcodeFormatClass = Class.forName("com.google.zxing.BarcodeFormat");
            Class<?> matrixToImageWriterClass = Class.forName("com.google.zxing.client.j2se.MatrixToImageWriter");

            @SuppressWarnings("unchecked")
            Map<Object, Object> hints = new HashMap<>();
            
            Object errorCorrectionLevel = errorCorrectionLevelClass.getField("M").get(null);
            Object charsetHint = encodeHintTypeClass.getField("CHARACTER_SET").get(null);
            Object marginHint = encodeHintTypeClass.getField("MARGIN").get(null);
            Object errorCorrectionHint = encodeHintTypeClass.getField("ERROR_CORRECTION").get(null);
            
            hints.put(errorCorrectionHint, errorCorrectionLevel);
            hints.put(charsetHint, "UTF-8");
            hints.put(marginHint, 1);

            Object qrCodeWriter = qrCodeWriterClass.getDeclaredConstructor().newInstance();
            Object qrCodeFormat = barcodeFormatClass.getField("QR_CODE").get(null);
            
            java.lang.reflect.Method encodeMethod = qrCodeWriterClass.getMethod("encode", String.class, 
                Class.forName("com.google.zxing.BarcodeFormat"), int.class, int.class, Map.class);
            Object bitMatrix = encodeMethod.invoke(qrCodeWriter, texto, qrCodeFormat, largura, altura, hints);

            java.lang.reflect.Method toBufferedImageMethod = matrixToImageWriterClass.getMethod("toBufferedImage", 
                Class.forName("com.google.zxing.common.BitMatrix"));
            BufferedImage qrImage = (BufferedImage) toBufferedImageMethod.invoke(null, bitMatrix);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "data:image/png;base64," + base64;
        } catch (ClassNotFoundException e) {
            System.err.println("Biblioteca ZXing não encontrada. Execute 'mvn clean install' para baixar as dependências.");
            return "";
        } catch (Exception e) {
            System.err.println("Erro ao gerar QR Code: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    private BigDecimal calcularINSSComTipo(BigDecimal valorBruto, String tipoImposto) {
        if (tipoImposto == null || "SEM_INSS".equalsIgnoreCase(tipoImposto)) {
            return BigDecimal.ZERO;
        }
        return calculoService.calcularINSS(valorBruto);
    }

    private String formatarDataISO(String dataISO) {
        try {
            // Formato esperado: YYYY-MM-DD
            java.time.LocalDate data = java.time.LocalDate.parse(dataISO);
            return data.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.of("pt", "BR")));
        } catch (Exception e) {
            // Se houver erro ao parsear, retorna a data original
            return dataISO;
        }
    }
}
