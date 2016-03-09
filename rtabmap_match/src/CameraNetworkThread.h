#pragma once

#include <rtabmap/utilite/UThread.h>
#include <rtabmap/utilite/UEventsHandler.h>

#include "CameraNetwork.h"

namespace rtabmap
{

class CameraNetworkThread :
    public UThread,
    public UEventsHandler
{
public:
    // ownership transferred
    CameraNetworkThread(CameraNetwork *camera, unsigned int dataBufferMaxSize = 1);
    virtual ~CameraNetworkThread();

    CameraNetwork *camera()
    {
        return _camera;   // return null if not set, valid until CameraNetworkThread is deleted
    }

protected:
    virtual void handleEvent(UEvent *event);

private:
    void mainLoopKill();
    void mainLoop();
    void addData(std::vector<unsigned char> *data, void *context = NULL);
    bool getData(std::vector<unsigned char> *&data, void *&context);

private:
    USemaphore _dataAdded;
    UMutex _dataMutex;
    std::list<std::vector<unsigned char> *> _dataBuffer;
    std::list<void *> _contextBuffer;
    unsigned int _dataBufferMaxSize;
    CameraNetwork *_camera;
};

} // namespace rtabmap