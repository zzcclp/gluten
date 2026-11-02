/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once
#include <jni.h>
#include <Formats/NativeReader.h>
#include <IO/BufferWithOwnMemory.h>
#include <IO/ReadBuffer.h>
#include <Storages/IO/NativeReader.h>
#include <Common/BlockIterator.h>
#include <Common/JNIUtils.h>

namespace DB
{
class CompressedReadBuffer;
class NativeReader;
}

namespace local_engine
{
void configureCompressedReadBuffer(DB::CompressedReadBuffer & compressedReadBuffer);
class ReadBufferFromJavaShuffleInputStream;
class ShuffleReader : BlockIterator
{
public:
    explicit ShuffleReader(
        std::unique_ptr<DB::ReadBuffer> in_, bool compressed, Int64 max_shuffle_read_rows_, Int64 max_shuffle_read_bytes_);
    DB::Block * read();
    ~ShuffleReader();
    static jclass shuffle_input_stream_class;
    static jmethodID shuffle_input_stream_read;

private:
    std::unique_ptr<DB::ReadBuffer> in;
    Int64 max_shuffle_read_rows;
    Int64 max_shuffle_read_bytes;
    std::unique_ptr<DB::ReadBuffer> compressed_in;
    std::unique_ptr<local_engine::NativeReader> input_stream;
    DB::Block header;
};


class ReadBufferFromJavaShuffleInputStream final : public DB::BufferWithOwnMemory<DB::ReadBuffer>
{
public:
    explicit ReadBufferFromJavaShuffleInputStream(jobject input_stream);
    ~ReadBufferFromJavaShuffleInputStream() override;

private:
    jobject java_in;
    int readFromJava() const;
    bool nextImpl() override;
};

/// Reads a Java byte[] by chunks.
/// The array is promoted to a global reference at construction, because the buffer is consumed by
/// the worker thread created in BroadcastJoinBuilder::buildJoin, while a JNI local reference is
/// only valid in the thread (and the frame) that created it.
class ReadBufferFromByteArray final : public DB::BufferWithOwnMemory<DB::ReadBuffer>
{
public:
    ReadBufferFromByteArray(JNIEnv * env, jbyteArray array_, size_t array_size_)
        : DB::BufferWithOwnMemory<DB::ReadBuffer>(std::min<size_t>(4 * DB::DBMS_DEFAULT_BUFFER_SIZE, array_size_))
        , array(env ? static_cast<jbyteArray>(env->NewGlobalRef(array_)) : nullptr)
        , array_size(array_size_)
        , read_pos(0)
    {
    }

    ~ReadBufferFromByteArray() override
    {
        if (!array)
            return;
        ScopedJniEnv scoped_env;
        if (scoped_env)
            scoped_env->DeleteGlobalRef(array);
    }

private:
    bool nextImpl() override;

    const jbyteArray array;
    const size_t array_size;
    size_t read_pos;
};

}
