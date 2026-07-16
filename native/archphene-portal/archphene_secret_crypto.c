#include "archphene_secret_crypto.h"

#include <errno.h>
#include <string.h>
#include <sys/random.h>

#include <mbedtls/bignum.h>
#include <mbedtls/cipher.h>
#include <mbedtls/hkdf.h>
#include <mbedtls/md.h>
#include <mbedtls/platform_util.h>

static const uint8_t modp_1024_prime[128] = {
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xc9, 0x0f, 0xda, 0xa2, 0x21, 0x68, 0xc2, 0x34,
    0xc4, 0xc6, 0x62, 0x8b, 0x80, 0xdc, 0x1c, 0xd1,
    0x29, 0x02, 0x4e, 0x08, 0x8a, 0x67, 0xcc, 0x74,
    0x02, 0x0b, 0xbe, 0xa6, 0x3b, 0x13, 0x9b, 0x22,
    0x51, 0x4a, 0x08, 0x79, 0x8e, 0x34, 0x04, 0xdd,
    0xef, 0x95, 0x19, 0xb3, 0xcd, 0x3a, 0x43, 0x1b,
    0x30, 0x2b, 0x0a, 0x6d, 0xf2, 0x5f, 0x14, 0x37,
    0x4f, 0xe1, 0x35, 0x6d, 0x6d, 0x51, 0xc2, 0x45,
    0xe4, 0x85, 0xb5, 0x76, 0x62, 0x5e, 0x7e, 0xc6,
    0xf4, 0x4c, 0x42, 0xe9, 0xa6, 0x37, 0xed, 0x6b,
    0x0b, 0xff, 0x5c, 0xb6, 0xf4, 0x06, 0xb7, 0xed,
    0xee, 0x38, 0x6b, 0xfb, 0x5a, 0x89, 0x9f, 0xa5,
    0xae, 0x9f, 0x24, 0x11, 0x7c, 0x4b, 0x1f, 0xe6,
    0x49, 0x28, 0x66, 0x51, 0xec, 0xe6, 0x53, 0x81,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff
};

void archphene_secret_crypto_wipe(void *value, size_t length) {
    if (value != NULL && length != 0) mbedtls_platform_zeroize(value, length);
}

static int secure_random(uint8_t *value, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = getrandom(value + offset, length - offset, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t)count;
    }
    return 0;
}

int archphene_secret_crypto_negotiate(
        const uint8_t *peer_public, size_t peer_public_length,
        uint8_t *local_public, size_t local_public_capacity,
        size_t *local_public_length,
        uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES]) {
    if (peer_public == NULL || peer_public_length == 0
            || peer_public_length > sizeof(modp_1024_prime)
            || local_public == NULL
            || local_public_capacity < ARCHPHENE_SECRET_DH_PUBLIC_MAX_BYTES
            || local_public_length == NULL || key == NULL) {
        errno = EINVAL;
        return -1;
    }

    int result = -1;
    uint8_t private_bytes[sizeof(modp_1024_prime)] = {0};
    uint8_t shared[sizeof(modp_1024_prime)] = {0};
    mbedtls_mpi prime;
    mbedtls_mpi generator;
    mbedtls_mpi peer;
    mbedtls_mpi private_value;
    mbedtls_mpi public_value;
    mbedtls_mpi shared_value;
    mbedtls_mpi upper_peer;
    mbedtls_mpi_init(&prime);
    mbedtls_mpi_init(&generator);
    mbedtls_mpi_init(&peer);
    mbedtls_mpi_init(&private_value);
    mbedtls_mpi_init(&public_value);
    mbedtls_mpi_init(&shared_value);
    mbedtls_mpi_init(&upper_peer);

    if (mbedtls_mpi_read_binary(&prime, modp_1024_prime,
                sizeof(modp_1024_prime)) != 0
            || mbedtls_mpi_lset(&generator, 2) != 0
            || mbedtls_mpi_read_binary(&peer, peer_public, peer_public_length) != 0
            || mbedtls_mpi_sub_int(&upper_peer, &prime, 2) != 0
            || mbedtls_mpi_cmp_int(&peer, 2) < 0
            || mbedtls_mpi_cmp_mpi(&peer, &upper_peer) > 0
            || secure_random(private_bytes, sizeof(private_bytes)) != 0) {
        errno = EINVAL;
        goto cleanup;
    }

    private_bytes[0] &= 0x7f;
    private_bytes[sizeof(private_bytes) - 1] |= 0x02;
    if (mbedtls_mpi_read_binary(&private_value, private_bytes,
                sizeof(private_bytes)) != 0
            || mbedtls_mpi_exp_mod(&public_value, &generator, &private_value,
                &prime, NULL) != 0
            || mbedtls_mpi_exp_mod(&shared_value, &peer, &private_value,
                &prime, NULL) != 0) {
        errno = EIO;
        goto cleanup;
    }

    size_t public_length = mbedtls_mpi_size(&public_value);
    if (public_length == 0 || public_length > local_public_capacity
            || mbedtls_mpi_write_binary(&public_value, local_public,
                public_length) != 0
            || mbedtls_mpi_write_binary(&shared_value, shared,
                sizeof(shared)) != 0) {
        errno = EIO;
        goto cleanup;
    }

    const mbedtls_md_info_t *sha256 =
            mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (sha256 == NULL || mbedtls_hkdf(sha256, NULL, 0,
            shared, sizeof(shared), NULL, 0,
            key, ARCHPHENE_SECRET_AES_KEY_BYTES) != 0) {
        errno = EIO;
        goto cleanup;
    }
    *local_public_length = public_length;
    result = 0;

cleanup:
    if (result != 0) {
        archphene_secret_crypto_wipe(local_public, local_public_capacity);
        archphene_secret_crypto_wipe(key, ARCHPHENE_SECRET_AES_KEY_BYTES);
    }
    archphene_secret_crypto_wipe(private_bytes, sizeof(private_bytes));
    archphene_secret_crypto_wipe(shared, sizeof(shared));
    mbedtls_mpi_free(&upper_peer);
    mbedtls_mpi_free(&shared_value);
    mbedtls_mpi_free(&public_value);
    mbedtls_mpi_free(&private_value);
    mbedtls_mpi_free(&peer);
    mbedtls_mpi_free(&generator);
    mbedtls_mpi_free(&prime);
    return result;
}

static int cipher_crypt(
        const uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES],
        const uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES],
        const uint8_t *input, size_t input_length,
        uint8_t *output, size_t output_capacity,
        size_t *output_length, mbedtls_operation_t operation) {
    if (key == NULL || iv == NULL || input == NULL || output == NULL
            || output_length == NULL) {
        errno = EINVAL;
        return -1;
    }
    size_t required = operation == MBEDTLS_ENCRYPT
            ? ((input_length / ARCHPHENE_SECRET_AES_IV_BYTES) + 1)
                    * ARCHPHENE_SECRET_AES_IV_BYTES
            : input_length;
    if (required > output_capacity
            || (operation == MBEDTLS_DECRYPT
                && (input_length == 0
                    || input_length % ARCHPHENE_SECRET_AES_IV_BYTES != 0))) {
        errno = EINVAL;
        return -1;
    }

    int result = -1;
    mbedtls_cipher_context_t cipher;
    mbedtls_cipher_init(&cipher);
    const mbedtls_cipher_info_t *info =
            mbedtls_cipher_info_from_type(MBEDTLS_CIPHER_AES_128_CBC);
    if (info == NULL
            || mbedtls_cipher_setup(&cipher, info) != 0
            || mbedtls_cipher_setkey(&cipher, key,
                ARCHPHENE_SECRET_AES_KEY_BYTES * 8, operation) != 0
            || mbedtls_cipher_set_padding_mode(&cipher,
                MBEDTLS_PADDING_PKCS7) != 0
            || mbedtls_cipher_crypt(&cipher, iv,
                ARCHPHENE_SECRET_AES_IV_BYTES, input, input_length,
                output, output_length) != 0
            || *output_length > output_capacity) {
        errno = EPROTO;
        archphene_secret_crypto_wipe(output, output_capacity);
    } else {
        result = 0;
    }
    mbedtls_cipher_free(&cipher);
    return result;
}

int archphene_secret_crypto_encrypt(
        const uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES],
        const uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES],
        const uint8_t *plaintext, size_t plaintext_length,
        uint8_t *ciphertext, size_t ciphertext_capacity,
        size_t *ciphertext_length) {
    return cipher_crypt(key, iv, plaintext, plaintext_length,
            ciphertext, ciphertext_capacity, ciphertext_length,
            MBEDTLS_ENCRYPT);
}

int archphene_secret_crypto_decrypt(
        const uint8_t key[ARCHPHENE_SECRET_AES_KEY_BYTES],
        const uint8_t iv[ARCHPHENE_SECRET_AES_IV_BYTES],
        const uint8_t *ciphertext, size_t ciphertext_length,
        uint8_t *plaintext, size_t plaintext_capacity,
        size_t *plaintext_length) {
    return cipher_crypt(key, iv, ciphertext, ciphertext_length,
            plaintext, plaintext_capacity, plaintext_length,
            MBEDTLS_DECRYPT);
}
