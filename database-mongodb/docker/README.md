# MongoDB Atlas Local - Vector Search Test Environment

This directory contains Docker configuration to run MongoDB Atlas Local with integrated mongot for vector search testing.

## Requirements

- Docker and Docker Compose installed
- ~2GB RAM available for the container

## Quick Start

```bash
# Start the environment
docker-compose up -d

# Wait for container to be healthy (about 30 seconds)
docker ps

# Verify MongoDB is running
docker logs mongodb-atlas-local | tail -20

# Stop the environment
docker-compose down

# Stop and remove volumes (clean start)
docker-compose down -v
```

## Services

| Service | Port | Description |
|---------|------|-------------|
| mongodb-atlas-local | 27017 | MongoDB 8.0 with bundled mongot |

## Connection String

For testing with this setup, use:

```
mongodb://localhost:27017/test?directConnection=true
```

## Running Vector Search Tests

Set the environment variable and run tests:

```bash
MONGO_VECTOR_TEST_URL="mongodb://localhost:27017/test?directConnection=true" \
  ./gradlew :database-mongodb:test --tests "MongodbVectorSearchTests"
```

## Creating a Vector Search Index

Vector search indexes can be created programmatically. The mongodb-atlas-local image supports the `createSearchIndexes` command. Example using mongosh:

```javascript
// Connect to MongoDB
mongosh "mongodb://localhost:27017/test?directConnection=true"

// Create a vector search index
db.runCommand({
  createSearchIndexes: "your_collection",
  indexes: [{
    name: "embedding_vector_index",
    type: "vectorSearch",
    definition: {
      fields: [{
        type: "vector",
        path: "embedding",
        numDimensions: 3,
        similarity: "cosine"
      }]
    }
  }]
})
```

## Troubleshooting

### Container won't start
Check logs:
```bash
docker logs mongodb-atlas-local
```

### Search queries fail with "SearchNotEnabled"
Ensure you're using the `mongodb-atlas-local` image, not the standalone community server. The atlas-local image bundles mongod and mongot pre-configured.

## Architecture

The `mongodb-atlas-local` Docker image bundles:
- MongoDB 8.0 Community Server (mongod)
- MongoDB Search (mongot) for vector and text search
- A runner process that initializes the replica set and connects mongod to mongot

This provides a complete local development experience matching MongoDB Atlas Search capabilities.
