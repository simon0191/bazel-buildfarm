// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.instance.stub;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheBlockingStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutionPolicy;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.ResultsCachePolicy;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.DigestUtil.ActionKey;
import build.buildfarm.common.function.InterruptingPredicate;
import build.buildfarm.instance.Instance;
import build.buildfarm.v1test.OperationQueueGrpc;
import build.buildfarm.v1test.OperationQueueGrpc.OperationQueueBlockingStub;
import build.buildfarm.v1test.PollOperationRequest;
import build.buildfarm.v1test.TakeOperationRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.SettableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

public class StubInstance implements Instance {
  private final String name;
  private final DigestUtil digestUtil;
  private final Channel channel;
  private final ByteStreamUploader uploader;

  public StubInstance(
      String name,
      DigestUtil digestUtil,
      Channel channel,
      ByteStreamUploader uploader) {
    this.name = name;
    this.digestUtil = digestUtil;
    this.channel = channel;
    this.uploader = uploader;
  }

  private final Supplier<ActionCacheBlockingStub> actionCacheBlockingStub =
      Suppliers.memoize(
          new Supplier<ActionCacheBlockingStub>() {
            @Override
            public ActionCacheBlockingStub get() {
              return ActionCacheGrpc.newBlockingStub(channel);
            }
          });

  private final Supplier<ContentAddressableStorageBlockingStub> contentAddressableStorageBlockingStub =
      Suppliers.memoize(
          new Supplier<ContentAddressableStorageBlockingStub>() {
            @Override
            public ContentAddressableStorageBlockingStub get() {
              return ContentAddressableStorageGrpc.newBlockingStub(channel);
            }
          });

  private final Supplier<ByteStreamBlockingStub> bsBlockingStub =
      Suppliers.memoize(
          new Supplier<ByteStreamBlockingStub>() {
            @Override
            public ByteStreamBlockingStub get() {
              return ByteStreamGrpc.newBlockingStub(channel);
            }
          });

  private final Supplier<ByteStreamStub> bsStub =
      Suppliers.memoize(
          new Supplier<ByteStreamStub>() {
            @Override
            public ByteStreamStub get() {
              return ByteStreamGrpc.newStub(channel);
            }
          });

  private final Supplier<OperationQueueBlockingStub> operationQueueBlockingStub =
      Suppliers.memoize(
          new Supplier<OperationQueueBlockingStub>() {
            @Override
            public OperationQueueBlockingStub get() {
              return OperationQueueGrpc.newBlockingStub(channel);
            }
          });

  @Override
  public String getName() {
    return name;
  }

  @Override
  public DigestUtil getDigestUtil() {
    return digestUtil;
  }

  @Override
  public ActionResult getActionResult(ActionKey actionKey) {
    return null;
  }

  @Override
  public void putActionResult(ActionKey actionKey, ActionResult actionResult) {
    // should we be checking the ActionResult return value?
    actionCacheBlockingStub.get().updateActionResult(UpdateActionResultRequest.newBuilder()
        .setInstanceName(getName())
        .setActionDigest(actionKey.getDigest())
        .setActionResult(actionResult)
        .build());
  }

  @Override
  public Iterable<Digest> findMissingBlobs(Iterable<Digest> digests) {
    FindMissingBlobsResponse response = contentAddressableStorageBlockingStub
        .get()
        .findMissingBlobs(FindMissingBlobsRequest.newBuilder()
            .setInstanceName(getName())
            .addAllBlobDigests(digests)
            .build());
    return response.getMissingBlobDigestsList();
  }

  @Override
  public Iterable<Digest> putAllBlobs(Iterable<ByteString> blobs)
      throws IOException, IllegalArgumentException, InterruptedException {
    // sort of a blatant misuse - one chunker per input, query digests before exhausting iterators
    Iterable<Chunker> chunkers = Iterables.transform(
        blobs, blob -> new Chunker(blob, digestUtil.compute(blob)));
    List<Digest> digests = new ImmutableList.Builder<Digest>()
        .addAll(Iterables.transform(chunkers, chunker -> chunker.digest()))
        .build();
    uploader.uploadBlobs(chunkers);
    return digests;
  }

  @Override
  public OutputStream getStreamOutput(String name) {
    return new OutputStream() {
      boolean closed = false;
      String resourceName = name;
      long written_bytes = 0;
      SettableFuture<WriteResponse> writeResponseFuture = SettableFuture.create();
      StreamObserver<WriteRequest> requestObserver = bsStub.get()
          .write(
              new StreamObserver<WriteResponse>() {
                @Override
                public void onNext(WriteResponse response) {
                  writeResponseFuture.set(response);
                }

                @Override
                public void onError(Throwable t) {
                  writeResponseFuture.setException(
                      new StatusRuntimeException(Status.fromThrowable(t)));
                }

                @Override
                public void onCompleted() {
                  if (!closed && !writeResponseFuture.isDone()) {
                    writeResponseFuture.setException(
                        new RuntimeException("Server closed connection before output stream."));
                  }
                }
              }
          );

      private long checkWriteResponse() throws IOException {
        try {
          return writeResponseFuture.get().getCommittedSize();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
          }
          if (e.getCause() instanceof IOException) {
            throw (IOException) e.getCause();
          }
          throw new IOException(e.getCause());
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }

      @Override
      public void close() throws IOException {
        boolean finish = !closed && !writeResponseFuture.isDone();
        if (finish) {
          closed = true;
          requestObserver.onNext(WriteRequest.newBuilder()
              .setResourceName(resourceName)
              .setFinishWrite(true)
              .build());
          requestObserver.onCompleted();
        }
        if (checkWriteResponse() != written_bytes) {
          throw new IOException("committed_size did not match bytes written");
        }
      }

      @Override
      public void write(int b) throws IOException {
        byte[] buf = new byte[1];
        buf[0] = (byte) b;
        write(buf);
      }

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
          throw new IOException();
        }
        if (writeResponseFuture.isDone()) {
          long committedSize = checkWriteResponse();
          throw new IOException("write response with committed_size " + committedSize + " received before write");
        }
        requestObserver.onNext(WriteRequest.newBuilder()
            .setResourceName(resourceName)
            .setData(ByteString.copyFrom(b, off, len))
            .setWriteOffset(written_bytes)
            .setFinishWrite(false)
            .build());
        written_bytes += len;
      }
    };
  }

  @Override
  public InputStream newStreamInput(String name) {
    Iterator<ReadResponse> replies = bsBlockingStub
        .get()
        .read(ReadRequest.newBuilder().setResourceName(name).build());
    return new ByteStringIteratorInputStream(Iterators.transform(replies, (reply) -> reply.getData()));
  }

  @Override
  public String getBlobName(Digest blobDigest) {
    return String.format(
        "%s/blobs/%s",
        getName(),
        DigestUtil.toString(blobDigest));
  }

  @Override
  public ByteString getBlob(Digest blobDigest) {
    if (blobDigest.getSizeBytes() == 0) {
      return ByteString.EMPTY;
    }
    try (InputStream in = newStreamInput(getBlobName(blobDigest))) {
      return ByteString.readFrom(in);
    } catch (IOException ex) {
      return null;
    }
  }

  @Override
  public ByteString getBlob(Digest blobDigest, long offset, long limit) {
    return null;
  }

  @Override
  public Digest putBlob(ByteString blob)
      throws IOException, IllegalArgumentException, InterruptedException {
    if (blob.size() == 0) {
      return digestUtil.empty();
    }
    Digest digest = digestUtil.compute(blob);
    Chunker chunker = new Chunker(blob, digest);
    uploader.uploadBlobs(Collections.singleton(chunker));
    return digest;
  }

  @Override
  public String getTree(
      Digest rootDigest,
      int pageSize,
      String pageToken,
      ImmutableList.Builder<Directory> directories) {
    Iterator<GetTreeResponse> replies = contentAddressableStorageBlockingStub
        .get()
        .getTree(GetTreeRequest.newBuilder()
            .setInstanceName(getName())
            .setRootDigest(rootDigest)
            .setPageSize(pageSize)
            .setPageToken(pageToken)
            .build());
    // new streaming interface doesn't really fit with what we're trying to do here...
    String nextPageToken = "";
    while (replies.hasNext()) {
      GetTreeResponse response = replies.next();
      directories.addAll(response.getDirectoriesList());
      nextPageToken = response.getNextPageToken();
    }
    return nextPageToken;
  }

  @Override
  public void execute(
      Digest actionDigest,
      boolean skipCacheLookup,
      ExecutionPolicy executionPolicy,
      ResultsCachePolicy resultsCachePolicy,
      Predicate<Operation> onOperation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void match(Platform platform, InterruptingPredicate<Operation> onMatch) throws InterruptedException {
    Operation operation = operationQueueBlockingStub.get()
        .take(TakeOperationRequest.newBuilder()
        .setInstanceName(getName())
        .setPlatform(platform)
        .build());
    onMatch.test(operation);
  }

  @Override
  public boolean putOperation(Operation operation) {
    return operationQueueBlockingStub
        .get()
        .put(operation)
        .getCode() == Code.OK.getNumber();
  }

  @Override
  public boolean putAndValidateOperation(Operation operation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean pollOperation(
      String operationName,
      ExecuteOperationMetadata.Stage stage) {
    return operationQueueBlockingStub
        .get()
        .poll(PollOperationRequest.newBuilder()
            .setOperationName(operationName)
            .setStage(stage)
            .build())
        .getCode() == Code.OK.getNumber();
  }

  @Override
  public boolean watchOperation(
      String operationName,
      Predicate<Operation> watcher) {
    return false;
  }

  @Override
  public String listOperations(
      int pageSize, String pageToken, String filter,
      ImmutableList.Builder<Operation> operations) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Operation getOperation(String operationName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteOperation(String operationName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void cancelOperation(String operationName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ServerCapabilities getCapabilities() {
    throw new UnsupportedOperationException();
  }
}
