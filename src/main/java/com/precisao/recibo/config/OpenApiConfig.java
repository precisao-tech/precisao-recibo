package com.precisao.recibo.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;

@OpenAPIDefinition(
        info = @Info(
                title = "API de Recibos",
                version = "1.0.0",
                description = "Documentação da API de emissão de recibos",
                contact = @Contact(name = "Equipe Precisão", email = "contato@precisao.com"),
                license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")
        ),
        servers = {
                @Server(url = "/", description = "Servidor atual")
        }
)
public class OpenApiConfig {
}

