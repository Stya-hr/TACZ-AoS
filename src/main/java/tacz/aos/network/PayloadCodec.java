package tacz.aos.network;

public interface PayloadCodec<B, T> {
    T decode(B buffer);
    void encode(B buffer, T message);
}
