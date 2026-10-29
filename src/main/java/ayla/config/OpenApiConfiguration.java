package ayla.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    public OpenAPI aylaOpenApi() {
        return new OpenAPI().components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("bearerAuth",
                        new io.swagger.v3.oas.models.security.SecurityScheme().type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("Opaque access token")))
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                .title("Ayla API")
                .version("v1")
                .description("""
                        API da assistente pessoal Ayla, com execução de agente e pesquisa pelo Hermes.

                        **Comece pelo POST /api/v1/chat:** envie apenas message para continuar a conversa atual.
                        Use searchRequired=true para exigir pesquisa ou conversationId para escolher uma conversa.
                        Crie e alterne conversas na seção Conversas. Consulte modelos gratuitos e configure o agente em Hermes.

                        Primeiro crie o login em /api/v1/auth/setup com a chave local X-Ayla-Setup-Key.
                        Entre em /api/v1/auth/login e use o accessToken no botão Authorize.
                        Tokens podem ser renovados em /api/v1/auth/refresh. Todos os dados pessoais exigem
                        autenticação; chaves internas do Hermes e WhatsApp ficam apenas nos servidores.
                        """));
    }
}
