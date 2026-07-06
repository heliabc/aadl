# 1. 启动 Ollama
ollama serve

# 2. 启动 Qdrant
docker run -p 6333:6333 -v qdrant_storage:/qdrant/storage qdrant/qdrant

# 3. 启动后端
cd 文件夹目录
mvn spring-boot:run

# 4. 打开浏览器访问
# http://localhost:8081/