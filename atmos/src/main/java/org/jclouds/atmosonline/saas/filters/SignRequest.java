/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.atmosonline.saas.filters;

import static org.jclouds.Constants.LOGGER_SIGNATURE;
import static org.jclouds.Constants.PROPERTY_CREDENTIAL;
import static org.jclouds.Constants.PROPERTY_IDENTITY;
import static org.jclouds.util.Patterns.NEWLINE_PATTERN;
import static org.jclouds.util.Patterns.TWO_SPACE_PATTERN;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.core.HttpHeaders;

import org.jclouds.atmosonline.saas.reference.AtmosStorageHeaders;
import org.jclouds.crypto.Crypto;
import org.jclouds.crypto.CryptoStreams;
import org.jclouds.date.TimeStamp;
import org.jclouds.http.HttpException;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpRequestFilter;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.internal.SignatureWire;
import org.jclouds.io.InputSuppliers;
import org.jclouds.logging.Logger;
import org.jclouds.util.Utils;

import com.google.common.annotations.VisibleForTesting;

/**
 * Signs the EMC Atmos Online Storage request.
 * 
 * @see <a href="https://community.emc.com/community/labs/atmos_online" />
 * @author Adrian Cole
 * 
 */
@Singleton
public class SignRequest implements HttpRequestFilter {

   private final SignatureWire signatureWire;
   private final String uid;
   private final byte[] key;
   private final Provider<String> timeStampProvider;
   private final Crypto crypto;
   private final HttpUtils utils;

   @Resource
   Logger logger = Logger.NULL;

   @Resource
   @Named(LOGGER_SIGNATURE)
   Logger signatureLog = Logger.NULL;

   @Inject
   public SignRequest(SignatureWire signatureWire, @Named(PROPERTY_IDENTITY) String uid,
            @Named(PROPERTY_CREDENTIAL) String encodedKey, @TimeStamp Provider<String> timeStampProvider,
            Crypto crypto, HttpUtils utils) {
      this.signatureWire = signatureWire;
      this.uid = uid;
      this.key = CryptoStreams.base64(encodedKey);
      this.timeStampProvider = timeStampProvider;
      this.crypto = crypto;
      this.utils = utils;
   }

   public void filter(HttpRequest request) throws HttpException {
      String toSign = replaceUIDHeader(request).removeOldSignature(request).replaceDateHeader(request)
               .createStringToSign(request);
      calculateAndReplaceAuthHeader(request, toSign);
      utils.logRequest(signatureLog, request, "<<");
   }

   private SignRequest removeOldSignature(HttpRequest request) {
      request.getHeaders().removeAll(AtmosStorageHeaders.SIGNATURE);
      return this;
   }

   public String createStringToSign(HttpRequest request) {
      utils.logRequest(signatureLog, request, ">>");
      StringBuilder buffer = new StringBuilder();
      // re-sign the request
      appendMethod(request, buffer);
      appendPayloadMetadata(request, buffer);
      appendHttpHeaders(request, buffer);
      appendCanonicalizedResource(request, buffer);
      appendCanonicalizedHeaders(request, buffer);
      if (signatureWire.enabled())
         signatureWire.output(buffer.toString());
      return buffer.toString();
   }

   private void calculateAndReplaceAuthHeader(HttpRequest request, String toSign) throws HttpException {
      String signature = signString(toSign);
      if (signatureWire.enabled())
         signatureWire.input(Utils.toInputStream(signature));
      request.getHeaders().replaceValues(AtmosStorageHeaders.SIGNATURE, Collections.singletonList(signature));
   }

   public String signString(String toSign) {
      String signature;
      try {
         signature = CryptoStreams.base64(CryptoStreams.mac(InputSuppliers.of(toSign), crypto.hmacSHA1(key)));
      } catch (Exception e) {
         throw new HttpException("error signing request", e);
      }
      return signature;
   }

   private void appendMethod(HttpRequest request, StringBuilder toSign) {
      toSign.append(request.getMethod()).append("\n");
   }

   SignRequest replaceUIDHeader(HttpRequest request) {
      request.getHeaders().replaceValues(AtmosStorageHeaders.UID, Collections.singletonList(uid));
      return this;
   }

   SignRequest replaceDateHeader(HttpRequest request) {
      request.getHeaders().replaceValues(HttpHeaders.DATE, Collections.singletonList(timeStampProvider.get()));
      return this;
   }

   private void appendCanonicalizedHeaders(HttpRequest request, StringBuilder toSign) {
      // TreeSet == Sort the headers alphabetically.
      Set<String> headers = new TreeSet<String>(request.getHeaders().keySet());
      for (String header : headers) {
         if (header.startsWith("x-emc-")) {
            // Convert all header names to lowercase.
            toSign.append(header.toLowerCase()).append(":");
            // For headers with values that span multiple lines, convert them into one line by
            // replacing any
            // newline characters and extra embedded white spaces in the value.
            for (String value : request.getHeaders().get(header)) {
               value = Utils.replaceAll(value, TWO_SPACE_PATTERN, " ");
               value = Utils.replaceAll(value, NEWLINE_PATTERN, "");
               toSign.append(value).append(' ');
            }
            toSign.deleteCharAt(toSign.lastIndexOf(" "));
            // Concatenate all headers together, using newlines (\n) separating each header from the
            // next one.
            toSign.append("\n");
         }
      }
      // There should be no terminating newline character at the end of the last header.
      if (toSign.charAt(toSign.length() - 1) == '\n')
         toSign.deleteCharAt(toSign.length() - 1);
   }

   private void appendPayloadMetadata(HttpRequest request, StringBuilder buffer) {
      buffer.append(utils.valueOrEmpty(request.getPayload() == null ? null : request.getPayload().getContentType()))
               .append("\n");
   }

   @VisibleForTesting
   void appendHttpHeaders(HttpRequest request, StringBuilder toSign) {
      // Only the value is used, not the header
      // name. If a request does not include the header, this is an empty string.
      for (String header : new String[] { "Range" })
         toSign.append(utils.valueOrEmpty(request.getHeaders().get(header)).toLowerCase()).append("\n");
      // Standard HTTP header, in UTC format. Only the date value is used, not the header name.
      toSign.append(request.getHeaders().get(HttpHeaders.DATE).iterator().next()).append("\n");
   }

   @VisibleForTesting
   void appendCanonicalizedResource(HttpRequest request, StringBuilder toSign) {
      // Path portion of the HTTP request URI, in lowercase.
      toSign.append(request.getEndpoint().getRawPath().toLowerCase()).append("\n");
   }

}