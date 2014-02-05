/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc. All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided that the following conditions are met: Redistributions of source code
 * must retain the above copyright notice, this list of conditions and the following disclaimer. Redistributions in
 * binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution. Neither the name of Salesforce.com nor the names
 * of its contributors may be used to endorse or promote products derived from this software without specific prior
 * written permission. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.index;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutationProto;

import com.google.common.collect.Multimap;
import com.salesforce.hbase.index.table.HTableInterfaceReference;
import com.salesforce.hbase.index.write.KillServerOnFailurePolicy;
import com.salesforce.phoenix.coprocessor.MetaDataProtocol;
import com.salesforce.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import com.salesforce.phoenix.coprocessor.MetaDataProtocol.MutationCode;
import com.salesforce.phoenix.coprocessor.generated.MetaDataProtos.DropColumnRequest;
import com.salesforce.phoenix.coprocessor.generated.MetaDataProtos.MetaDataResponse;
import com.salesforce.phoenix.coprocessor.generated.MetaDataProtos.MetaDataService;
import com.salesforce.phoenix.coprocessor.generated.MetaDataProtos.UpdateIndexStateRequest;
import com.salesforce.phoenix.jdbc.PhoenixDatabaseMetaData;
import com.salesforce.phoenix.protobuf.ProtobufUtil;
import com.salesforce.phoenix.schema.PIndexState;
import com.salesforce.phoenix.util.SchemaUtil;

/**
 * 
 * Handler called in the event that index updates cannot be written to their
 * region server. First attempts to disable the index and failing that falls
 * back to the default behavior of killing the region server.
 *
 * TODO: use delegate pattern instead
 * 
 * @author jtaylor
 * @since 2.1
 */
public class PhoenixIndexFailurePolicy extends  KillServerOnFailurePolicy {
    private static final Log LOG = LogFactory.getLog(PhoenixIndexFailurePolicy.class);
    private RegionCoprocessorEnvironment env;

    public PhoenixIndexFailurePolicy() {
    }

    @Override
    public void setup(Stoppable parent, RegionCoprocessorEnvironment env) {
      super.setup(parent, env);
      this.env = env;
    }

    @Override
    public void handleFailure(Multimap<HTableInterfaceReference, Mutation> attempted, Exception cause) throws IOException {
        Set<HTableInterfaceReference> refs = attempted.asMap().keySet();
        StringBuilder buf = new StringBuilder("Disabled index" + (refs.size() > 1 ? "es " : " "));
        try {
            for (HTableInterfaceReference ref : refs) {
                // Disable the index by using the updateIndexState method of MetaDataProtocol end point coprocessor.
                String indexTableName = ref.getTableName();
                byte[] indexTableKey = SchemaUtil.getTableKeyFromFullName(indexTableName);
                HTableInterface systemTable = env.getTable(TableName.valueOf(PhoenixDatabaseMetaData.TYPE_TABLE_NAME_BYTES));
                // Mimic the Put that gets generated by the client on an update of the index state
                Put put = new Put(indexTableKey);
                put.add(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_STATE_BYTES, PIndexState.DISABLE.getSerializedBytes());
                final List<Mutation> tableMetadata = Collections.<Mutation>singletonList(put);
                
                final Map<byte[], MetaDataResponse> results = 
                        systemTable.coprocessorService(MetaDataService.class, indexTableKey, indexTableKey,
                            new Batch.Call<MetaDataService, MetaDataResponse>() {
                                @Override
                                public MetaDataResponse call(MetaDataService instance) throws IOException {
                                    ServerRpcController controller = new ServerRpcController();
                                    BlockingRpcCallback<MetaDataResponse> rpcCallback =
                                            new BlockingRpcCallback<MetaDataResponse>();
                                    UpdateIndexStateRequest.Builder builder = UpdateIndexStateRequest.newBuilder();
                                    for (Mutation m : tableMetadata) {
                                        MutationProto mp = ProtobufUtil.toProto(m);
                                        builder.addTableMetadataMutations(mp.toByteString());
                                    }
                                    instance.updateIndexState(controller, builder.build(), rpcCallback);
                                    if(controller.getFailedOn() != null) {
                                        throw controller.getFailedOn();
                                    }
                                    return rpcCallback.get();
                                }
                            });
                if(results.isEmpty()){
                    throw new IOException("Didn't get expected result size");
                }
                MetaDataResponse tmpResponse = results.values().iterator().next();
                MetaDataMutationResult result = MetaDataMutationResult.constructFromProto(tmpResponse);                
                
                if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS) {
                    LOG.warn("Attempt to disable index " + indexTableName + " failed with code = " + result.getMutationCode() + ". Will use default failure policy instead.");
                    super.handleFailure(attempted, cause);
                }
                LOG.info("Successfully disabled index " + indexTableName);
                buf.append(indexTableName);
                buf.append(',');
            }
            buf.setLength(buf.length()-1);
            buf.append(" due to an exception while writing updates");
        } catch (Throwable t) {
            super.handleFailure(attempted, cause);
        }
        throw new DoNotRetryIOException(buf.toString(), cause);
    }

}