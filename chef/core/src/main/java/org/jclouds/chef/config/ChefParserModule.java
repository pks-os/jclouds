/**
 *
 * Copyright (C) 2010 Cloud Conscious, LLC. <info@cloudconscious.com>
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
package org.jclouds.chef.config;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.chef.domain.DataBagItem;
import org.jclouds.crypto.Crypto;
import org.jclouds.crypto.Pems;
import org.jclouds.io.InputSuppliers;
import org.jclouds.json.config.GsonModule.DateAdapter;
import org.jclouds.json.config.GsonModule.Iso8601DateAdapter;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonLiteral;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.inject.AbstractModule;
import com.google.inject.ImplementedBy;
import com.google.inject.Provides;

/**
 * 
 * 
 * @author Adrian Cole
 */
public class ChefParserModule extends AbstractModule {
   @ImplementedBy(PrivateKeyAdapterImpl.class)
   public static interface PrivateKeyAdapter extends JsonDeserializer<PrivateKey> {

   }

   @Singleton
   public static class PrivateKeyAdapterImpl implements PrivateKeyAdapter {
      private final Crypto crypto;

      @Inject
      PrivateKeyAdapterImpl(Crypto crypto) {
         this.crypto = crypto;
      }

      @Override
      public PrivateKey deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
               throws JsonParseException {
         String keyText = json.getAsString().replaceAll("\\n", "\n");
         try {
            return crypto.rsaKeyFactory().generatePrivate(Pems.privateKeySpec(InputSuppliers.of(keyText)));
         } catch (UnsupportedEncodingException e) {
            Throwables.propagate(e);
            return null;
         } catch (InvalidKeySpecException e) {
            Throwables.propagate(e);
            return null;
         } catch (IOException e) {
            Throwables.propagate(e);
            return null;
         }
      }
   }

   @ImplementedBy(PublicKeyAdapterImpl.class)
   public static interface PublicKeyAdapter extends JsonDeserializer<PublicKey> {

   }

   @Singleton
   public static class PublicKeyAdapterImpl implements PublicKeyAdapter {
      private final Crypto crypto;

      @Inject
      PublicKeyAdapterImpl(Crypto crypto) {
         this.crypto = crypto;
      }

      @Override
      public PublicKey deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
               throws JsonParseException {
         String keyText = json.getAsString().replaceAll("\\n", "\n");
         try {
            return crypto.rsaKeyFactory().generatePublic(Pems.publicKeySpec(InputSuppliers.of(keyText)));
         } catch (UnsupportedEncodingException e) {
            Throwables.propagate(e);
            return null;
         } catch (InvalidKeySpecException e) {
            Throwables.propagate(e);
            return null;
         } catch (IOException e) {
            Throwables.propagate(e);
            return null;
         }
      }
   }

   @ImplementedBy(X509CertificateAdapterImpl.class)
   public static interface X509CertificateAdapter extends JsonDeserializer<X509Certificate> {

   }

   @Singleton
   public static class X509CertificateAdapterImpl implements X509CertificateAdapter {
      private final Crypto crypto;

      @Inject
      X509CertificateAdapterImpl(Crypto crypto) {
         this.crypto = crypto;
      }

      @Override
      public X509Certificate deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
               throws JsonParseException {
         String keyText = json.getAsString().replaceAll("\\n", "\n");
         try {
            return Pems.x509Certificate(InputSuppliers.of(keyText), crypto.certFactory());
         } catch (UnsupportedEncodingException e) {
            Throwables.propagate(e);
            return null;
         } catch (IOException e) {
            Throwables.propagate(e);
            return null;
         } catch (CertificateException e) {
            Throwables.propagate(e);
            return null;
         }
      }
   }

   @ImplementedBy(DataBagItemAdapterImpl.class)
   public static interface DataBagItemAdapter extends JsonSerializer<DataBagItem>, JsonDeserializer<DataBagItem> {

   }

   @Singleton
   public static class DataBagItemAdapterImpl implements DataBagItemAdapter {

      @Override
      public JsonElement serialize(DataBagItem src, Type typeOfSrc, JsonSerializationContext context) {
         return new JsonLiteral(src.toString());
      }

      @Override
      public DataBagItem deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
               throws JsonParseException {
         IdHolder idHolder = context.deserialize(json, IdHolder.class);
         return new DataBagItem(idHolder.id, json.toString());
      }
   }

   private static class IdHolder {
      private String id;
   }

   @Provides
   @Singleton
   @Named(Constants.PROPERTY_GSON_ADAPTERS)
   public Map<Type, Object> provideCustomAdapterBindings(DataBagItemAdapter adapter, PrivateKeyAdapter privateAdapter,
            PublicKeyAdapter publicAdapter, X509CertificateAdapter certAdapter) {
      return ImmutableMap.<Type, Object> of(DataBagItem.class, adapter, PrivateKey.class, privateAdapter,
               PublicKey.class, publicAdapter, X509Certificate.class, certAdapter);
   }

   @Override
   protected void configure() {
      bind(DateAdapter.class).to(Iso8601DateAdapter.class);
   }
}