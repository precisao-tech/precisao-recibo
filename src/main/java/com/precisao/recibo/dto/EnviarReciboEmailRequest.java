package com.precisao.recibo.dto;

public record EnviarReciboEmailRequest(
        String emailDestinatario,
        String nomeDestinatario,
        String assunto,
        ReciboRequest dadosRecibo
) {
    public EnviarReciboEmailRequest {
        if (emailDestinatario == null || emailDestinatario.isBlank()) {
            throw new IllegalArgumentException("Email do destinatário é obrigatório");
        }
        if (dadosRecibo == null) {
            throw new IllegalArgumentException("Dados do recibo são obrigatórios");
        }
        if (assunto == null || assunto.isBlank()) {
            assunto = "Recibo Eletronico - Pró-Labore";
        }
    }
}



