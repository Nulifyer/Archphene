#ifndef ARCHPHENE_SECRET_CRYPTO_H
#define ARCHPHENE_SECRET_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#define ARCHPHENE_SECRET_AES_KEY_BYTES 16
#define ARCHPHENE_SECRET_AES_IV_BYTES 16
#define ARCHPHENE_SECRET_DH_PUBLIC_MAX_BYTES 128

int archphene_secret_crypto_negotiate(
        const uint8_t *peer_public, size_t peer_public_length,
        uint8_t *local_public, size_t local_public_capacity,
        size_t *local_public_length,
        uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES]);

int archphene_secret_crypto_encrypt(
        const uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES],
        const uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES],
        const uint8_t *plaintext, size_t plaintext_length,
        uint8_t *ciphertext, size_t ciphertext_capacity,
        size_t *ciphertext_length);

int archphene_secret_crypto_decrypt(
        const uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES],
        const uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES],
        const uint8_t *ciphertext, size_t ciphertext_length,
        uint8_t *plaintext, size_t plaintext_capacity,
        size_t *plaintext_length);

void archphene_secret_crypto_wipe(void *value, size_t length);

#endif
