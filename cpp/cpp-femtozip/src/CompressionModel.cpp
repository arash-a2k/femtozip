/**
 *   Copyright 2011 Garrick Toubassi
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
/*
 * CompressionModel.cpp
 *
 *  Created on: Mar 4, 2011
 *      Author: gtoubassi
 */

#include "CompressionModel.h"
#include "DictionaryOptimizer.h"

namespace femtozip {

CompressionModel::CompressionModel() : dict(0), dictLen(0) {

}

CompressionModel::~CompressionModel() {
    if (dict) {
        delete[] dict;
    }
}

void CompressionModel::load(DataInput& in) {
    in >> dictLen;
    dict = 0;
    if (dictLen > 0) {
        char *d = new char[dictLen];
        in.read(d, dictLen);
        dict = d;
    }

}

void CompressionModel::save(DataOutput& out) {
    out << dictLen;
    if (dictLen > 0) {
        out.write(dict, dictLen);
    }
}

void CompressionModel::setDictionary(const char *dictionary, int length) {
    char *d = new char[length];
    memcpy(d, dictionary, length);
    dict = d;
    dictLen = length;
}

const char *CompressionModel::getDictionary(int& length) {
    length = dictLen;
    return dict;
}

void CompressionModel::compress(const char *buf, int length, ostream& out) {
    SubstringPacker packer(dict, dictLen);
    packer.pack(buf, length, *this);

}

SubstringPacker::Consumer *CompressionModel::createModelBuilder() {
    return 0;
}


SubstringPacker::Consumer *CompressionModel::buildEncodingModel(DocumentList& documents) {
    SubstringPacker modelBuildingPacker(dict, dictLen);
    SubstringPacker::Consumer *modelBuilder = createModelBuilder();

    for (int i = 0, count = documents.size(); i < count; i++) {
        int length;
        const char * docBytes = documents.get(i, length);
        modelBuildingPacker.pack(docBytes, length, *modelBuilder);
        delete[] docBytes;
    }

    return modelBuilder;
}

void CompressionModel::buildDictionaryIfUnspecified(DocumentList& documents) {
    if (!dict) {
        DictionaryOptimizer optimizer(documents);
        string dictionary = optimizer.optimize(64*1024);
        setDictionary(dictionary.c_str(), dictionary.length());
    }
}


}
