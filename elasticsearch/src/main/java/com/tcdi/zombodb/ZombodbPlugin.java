/*
 * Copyright 2013-2015 Technology Concepts & Design, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tcdi.zombodb;

import com.tcdi.zombodb.postgres.*;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestModule;

/**
 * @author e_ridge
 */
public class ZombodbPlugin extends Plugin {

    @Inject
    public ZombodbPlugin(Settings settings) {
        // noop
    }

    public void onModule(RestModule module) {
        module.addRestAction(PostgresTIDResponseAction.class);
        module.addRestAction(PostgresAggregationAction.class);
        module.addRestAction(PostgresCountAction.class);
        module.addRestAction(PostgresMappingAction.class);
        module.addRestAction(ZombodbQueryAction.class);
        module.addRestAction(ZombodbDocumentHighlighterAction.class);
        module.addRestAction(ZombodbMultisearchAction.class);
    }

    @Override
    public String name() {
        return "Zombodb";
    }

    @Override
    public String description() {
        return "REST endpoints in support of ZomboDB";
    }
}
