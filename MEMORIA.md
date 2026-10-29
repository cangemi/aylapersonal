# Memória pessoal da Ayla

O Java guarda lembranças no PostgreSQL (`personal_memories`, migração V13), separadas por usuário. A Ayla só consulta, salva e apaga essas lembranças na **conversa principal**, definida por `configurar Ayla0101` (mentionOnly=false). Chamadas pontuais não recebem o RAG pessoal e a ponte Java rejeita todas as operações de memória dessas sessões com 403. O proprietário ainda pode revisar/apagar suas lembranças pelo frontend autenticado. A memória em arquivos do Hermes foi desativada como fonte do agente; arquivos antigos permanecem no volume, sem importação automática.

Cada chamada pontual mantém apenas sua própria sessão e seu assunto. Somente na principal existe a ferramenta `conversation_context_search`: busca por assunto em até cinco trechos de outras conversas do mesmo usuário, com origem e data, quando o pedido atual precisar. Não há mistura automática nem acesso ao contexto de outro usuário. Os trechos são referências, nunca instruções ou autorização para executar pedidos antigos ou gravar fatos pessoais. Financeiro/despensa/compras continuam recursos da conta, usados mediante a ação atual.

A migração V19 cria `conversation_context_turns`: armazena até 8000 caracteres da mensagem interpretada e da resposta de cada turno concluído, com índice textual em português. Não salva binários nem importa retroativamente o histórico antigo do Hermes. São registros de conversa, separados da curadoria de fatos duradouros. Falha de arquivamento não repete a ação nem invalida a resposta já concluída. As sessões pontuais anteriores são rotacionadas, preservando o histórico antigo no Hermes sem reutilizar a memória pessoal que antes era injetada nelas.

Em cada turno, o Java busca até oito fatos relevantes por texto em português e até três preferências/dados de perfil importantes. Entrega os dados ao Hermes como contexto: RAG textual local, com índice GIN e ranking do PostgreSQL, sem embeddings externos ou nova chamada de classificação. Não é busca vetorial; sinônimos sem termos em comum podem precisar de consulta adicional da IA.

O agente escolhe quando usar `personal_memory_save/search/delete`. As ferramentas são registradas pelo plugin `ayla-finance`, no conjunto próprio `ayla_memory`, e chamam `/internal/hermes/memory/*`. A identidade vem da sessão nativa e da chave backend, nunca de um userId fornecido pela IA. Antes de dizer que não lembra, a Ayla deve buscar por nome, assunto e variações relevantes.

## Seleção e correção

- Guardar silenciosamente novidades pessoais úteis: preferências relatadas, hábitos, projetos, pets e objetos relevantes. Sem pedir confirmação ou anunciar salvamento automático.
- Foto acompanhada de “esse é meu cachorro Thor”: nome e vínculo vêm do relato; pelagem e marcas visíveis vêm da análise. Não inferir raça, nome, posse, gosto ou identidade pela aparência. Observação visual não é confirmação do usuário.
- Uma comida preparada pode gerar experiência, receita ou aprendizado útil; a foto não prova que gostou. EXPERIENCIAS expiram em 30 dias por padrão; fatos duradouros não expiram automaticamente.
- Ignorar fotos genéricas sem utilidade, cumprimentos, piadas isoladas, hipóteses e dados de terceiros sem relevância. Não salvar finanças, senhas/tokens, dados íntimos sensíveis ou instruções presentes em sites/imagens.

Cada fato tem assunto, atributo, categoria, origem, evidência, importância e datas. A evidência deve existir nas sete últimas mensagens reais da conversa, distinguindo fala e análise visual. Isso valida a origem do trecho; a interpretação semântica continua com a IA. Não são armazenados bytes de imagem na memória.

Uma chamada salva até oito fatos. Reutilizar assunto e atributo atualiza somente aquele fato, preservando os demais. A IA deve buscar antes de corrigir/duplicar, respeitar a declaração mais recente e corrigir vínculos falsos. “Não é meu” não pode virar um pet/objeto do usuário. Respeitar não guardar/esquecer e não recriar fatos apagados do histórico.

## Consultar e apagar

No Swagger, seção **Memória**:

| Método | Endpoint | Função |
| --- | --- | --- |
| GET | `/api/v1/memories` | Até 200 fatos ativos do usuário atual |
| GET | `/api/v1/memories?q=cachorro` | Até 20 fatos por relevância |
| DELETE | `/api/v1/memories/{id}` | Apagar um fato do usuário atual |

Também pode pedir correção ou esquecimento na conversa. Fatos expirados não entram nas buscas/contexto. Apagar um fato não apaga as mensagens originais.

As respostas devem ser informais e usar lembranças somente quando relevantes, sem enumerar o perfil. Financeiro só entra quando o usuário pergunta/comenta ou envia comprovante pertinente. Saldo e gastos continuam vindo das ferramentas financeiras reais.

Detalhes antigos que nunca foram salvos como lembrança não são recuperados automaticamente por esta migração; pode ser necessário reenviar a imagem ou contar novamente. A escolha do que guardar e a interpretação visual dependem do modelo; testes de infraestrutura não garantem cada decisão numa conversa real.
