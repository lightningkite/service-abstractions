# MongoDB Atlas Local for vector search tests

`docker-compose.yml` runs `mongodb/mongodb-atlas-local`, which bundles `mongod` and `mongot`, so Atlas vector and text search work locally (about 2 GB of RAM).

The vector tests (`MongoVectorIndexTest`) start this container themselves when Docker is available and skip otherwise.  To run against a container you started, or a real Atlas cluster, set `MONGO_VECTOR_TEST_URL`:

```bash
docker-compose up -d          # healthy after about 30 seconds
MONGO_VECTOR_TEST_URL="mongodb://localhost:27017/test?directConnection=true" \
  ./gradlew :database-mongodb:test --tests "*MongoVectorIndexTest*"
docker-compose down -v        # stop and delete data
```

`VectorDatabase.prepare` creates the search indexes; nothing needs creating by hand.  "SearchNotEnabled" errors mean the server isn't the atlas-local image (plain community `mongod` has no `mongot`).

Usage: [docs/database.md#vector-search](../../docs/database.md#vector-search).
