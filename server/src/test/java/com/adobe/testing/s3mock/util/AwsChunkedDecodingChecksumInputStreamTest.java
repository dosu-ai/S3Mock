/*
 *  Copyright 2017-2024 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.adobe.testing.s3mock.util;

import static com.adobe.testing.s3mock.dto.ChecksumAlgorithm.SHA256;
import static com.adobe.testing.s3mock.util.AwsHttpHeaders.X_AMZ_CHECKSUM_SHA256;
import static com.adobe.testing.s3mock.util.DigestUtil.checksumFor;
import static com.adobe.testing.s3mock.util.TestUtil.getFileFromClasspath;
import static java.nio.file.Files.newInputStream;
import static org.assertj.core.api.Assertions.assertThat;

import com.adobe.testing.s3mock.dto.ChecksumAlgorithm;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.signer.internal.chunkedencoding.AwsS3V4ChunkSigner;
import software.amazon.awssdk.auth.signer.internal.chunkedencoding.AwsSignedChunkedEncodingInputStream;
import software.amazon.awssdk.core.checksums.Algorithm;
import software.amazon.awssdk.core.checksums.SdkChecksum;
import software.amazon.awssdk.core.internal.chunked.AwsChunkedEncodingConfig;

class AwsChunkedDecodingChecksumInputStreamTest {

  @Test
  void testDecode_checksum(TestInfo testInfo) throws IOException {
    doTest(testInfo, "sampleFile.txt", X_AMZ_CHECKSUM_SHA256, Algorithm.SHA256,
        "1VcEifAruhjVvjzul4sC0B1EmlUdzqvsp6BP0KSVdTE=", SHA256, 1);
    doTest(testInfo, "sampleFile_large.txt", X_AMZ_CHECKSUM_SHA256, Algorithm.SHA256,
        "Y8S4/uAGut7vjdFZQjLKZ7P28V9EPWb4BIoeniuM0mY=", SHA256, 16);
  }

  @Test
  void testDecode_noChecksum(TestInfo testInfo) throws IOException {
    doTest(testInfo, "sampleFile.txt", 1);
    doTest(testInfo, "sampleFile_large.txt", 16);
  }

  void doTest(TestInfo testInfo, String fileName, int chunks) throws IOException {
    doTest(testInfo, fileName, null, null, null, null, chunks);
  }

  void doTest(TestInfo testInfo, String fileName, String header, Algorithm algorithm,
              String checksum, ChecksumAlgorithm checksumAlgorithm, int chunks) throws IOException {
    File sampleFile = getFileFromClasspath(testInfo, fileName);
    AwsSignedChunkedEncodingInputStream.Builder builder = AwsSignedChunkedEncodingInputStream
        .builder()
        .inputStream(Files.newInputStream(sampleFile.toPath()));
    if (algorithm != null) {
      builder.sdkChecksum(SdkChecksum.forAlgorithm(algorithm));
    }
    InputStream chunkedEncodingInputStream = builder
        .checksumHeaderForTrailer(header)
        //force chunks in the inputstream
        .awsChunkedEncodingConfig(AwsChunkedEncodingConfig.builder().chunkSize(4000).build())
        .awsChunkSigner(new AwsS3V4ChunkSigner("signingKey".getBytes(),
            "dateTime",
            "keyPath"))
        .build();

    long decodedLength = sampleFile.length();
    AwsChunkedDecodingChecksumInputStream iut = new
        AwsChunkedDecodingChecksumInputStream(chunkedEncodingInputStream, decodedLength);
    assertThat(iut).hasSameContentAs(Files.newInputStream(sampleFile.toPath()));
    assertThat(iut.getAlgorithm()).isEqualTo(checksumAlgorithm);
    assertThat(iut.getChecksum()).isEqualTo(checksum);
    assertThat(iut.decodedLength).isEqualTo(decodedLength);
    assertThat(iut.readDecodedLength).isEqualTo(decodedLength);
    assertThat(iut.chunks).isEqualTo(chunks);
  }

  @ParameterizedTest
  @MethodSource("algorithms")
  void testDecode_signed_checksum(Algorithm algorithm, TestInfo testInfo) throws IOException {
    ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.fromString(algorithm.toString());
    String header = HeaderUtil.mapChecksumToHeader(checksumAlgorithm);
    doTestSigned(getFileFromClasspath(testInfo, "sampleFile.txt"),
        1,
        header,
        SdkChecksum.forAlgorithm(algorithm),
        checksumFor(
            getFileFromClasspath(testInfo, "sampleFile.txt").toPath(), algorithm),
        checksumAlgorithm
    );
    doTestSigned(getFileFromClasspath(testInfo, "sampleFile_large.txt"),
        16,
        header,
        SdkChecksum.forAlgorithm(algorithm),
        checksumFor(
            getFileFromClasspath(testInfo, "sampleFile_large.txt").toPath(), algorithm),
        checksumAlgorithm
    );
    doTestSigned(getFileFromClasspath(testInfo, "test-image-small.png"),
        9,
        header,
        SdkChecksum.forAlgorithm(algorithm),
        checksumFor(
            getFileFromClasspath(testInfo, "test-image-small.png").toPath(), algorithm),
        checksumAlgorithm
    );
    doTestSigned(getFileFromClasspath(testInfo, "test-image.png"),
        17,
        header,
        SdkChecksum.forAlgorithm(algorithm),
        checksumFor(
            getFileFromClasspath(testInfo, "test-image.png").toPath(), algorithm),
        checksumAlgorithm
    );
  }

  @Test
  void testDecode_signed_noChecksum(TestInfo testInfo) throws IOException {
    doTestSigned(getFileFromClasspath(testInfo, "sampleFile.txt"), 1);
    doTestSigned(getFileFromClasspath(testInfo, "sampleFile_large.txt"), 16);
    doTestSigned(getFileFromClasspath(testInfo, "test-image-small.png"), 9);
    doTestSigned(getFileFromClasspath(testInfo, "test-image.png"), 17);
  }

  void doTestSigned(File input, int chunks) throws IOException {
    doTestSigned(input, chunks, null, null, null, null);
  }

  void doTestSigned(File input, int chunks, String header, SdkChecksum algorithm,
                    String checksum, ChecksumAlgorithm checksumAlgorithm) throws IOException {
    InputStream chunkedEncodingInputStream = AwsSignedChunkedEncodingInputStream
        .builder()
        .inputStream(newInputStream(input.toPath()))
        .sdkChecksum(algorithm)
        .checksumHeaderForTrailer(header)
        //force chunks in the inputstream
        .awsChunkedEncodingConfig(AwsChunkedEncodingConfig.builder().chunkSize(4000).build())
        .awsChunkSigner(new AwsS3V4ChunkSigner("signingKey".getBytes(),
            "dateTime",
            "keyPath"))
        .build();

    long decodedLength = input.length();
    AwsChunkedDecodingChecksumInputStream iut =
        new AwsChunkedDecodingChecksumInputStream(chunkedEncodingInputStream, decodedLength);

    assertThat(iut).hasSameContentAs(newInputStream(input.toPath()));
    assertThat(iut.getAlgorithm()).isEqualTo(checksumAlgorithm);
    assertThat(iut.getChecksum()).isEqualTo(checksum);
    assertThat(iut.decodedLength).isEqualTo(decodedLength);
    assertThat(iut.readDecodedLength).isEqualTo(decodedLength);
    assertThat(iut.chunks).isEqualTo(chunks);
  }

  private static Stream<Algorithm> algorithms() {
    return Arrays.stream(Algorithm.values());
  }
}
