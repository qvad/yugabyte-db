// Copyright (c) YugabyteDB, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#pragma once

#include "yb/util/enums.h"

#include "yb/common/vector_types.h"
#include "yb/vector/graph_repr_defs.h"

namespace yb::vectorindex {

namespace distance {

float DistanceL2Squared(const FloatVector& a, const FloatVector& b);
float DistanceCosine(const FloatVector& a, const FloatVector& b);

}  // namespace distance

YB_DEFINE_ENUM(
    VectorDistanceType,
    (kL2Squared)
    (kCosine));

using DistanceFunction = std::function<float(const FloatVector&, const FloatVector&)>;

// A variant of a distance function that knows how to resolve a vertex id to a vector, and then
// compute the distance.
using VertexIdToVectorDistanceFunction =
    std::function<float(VertexId vertex_id, const FloatVector&)>;

DistanceFunction GetDistanceImpl(VectorDistanceType distance_type);

struct VertexWithDistance {
  VertexId vertex_id = 0;
  float distance = 0.0f;

  // Deleted constructor to prevent wrong initialization order
  VertexWithDistance(float, VertexId) = delete;

  VertexWithDistance() = default;

  // Constructor with the correct order
  VertexWithDistance(VertexId vertex_id_, float distance_)
      : vertex_id(vertex_id_), distance(distance_) {}

  std::string ToString() const;

  bool operator ==(const VertexWithDistance& other) const {
    return vertex_id == other.vertex_id && distance == other.distance;
  }

  bool operator !=(const VertexWithDistance& other) const {
    return !(*this == other);
  }

  // Sort in lexicographical order of (distance, vertex_id).
  bool operator <(const VertexWithDistance& other) const {
    return distance < other.distance ||
           (distance == other.distance && vertex_id < other.vertex_id);
  }

  bool operator>(const VertexWithDistance& other) const {
    return distance > other.distance ||
           (distance == other.distance && vertex_id > other.vertex_id);
  }
};

using VerticesWithDistances = std::vector<VertexWithDistance>;

std::vector<VertexId> VertexIdsOnly(const VerticesWithDistances& vertices_with_distances);

}  // namespace yb::vectorindex
