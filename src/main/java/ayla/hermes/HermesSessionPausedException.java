package ayla.hermes;

/** No new request has been admitted; the earlier interrupted execution needs recovery. */
public class HermesSessionPausedException extends HermesException {
    public HermesSessionPausedException(){super(409,"Esta conversa está pausada no Hermes por uma execução interrompida. Este novo pedido não foi enviado à IA. A sessão precisa ser recuperada sem repetir os pedidos antigos.");}
}
