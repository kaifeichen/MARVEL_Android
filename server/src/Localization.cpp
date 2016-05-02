#include <rtabmap/core/Signature.h>
#include <rtabmap/utilite/ULogger.h>
#include <rtabmap/utilite/UTimer.h>
#include <rtabmap/utilite/UConversion.h>
#include <rtabmap/utilite/UStl.h>
#include <rtabmap/utilite/UMath.h>
#include <rtabmap/core/VWDictionary.h>
#include <rtabmap/core/Rtabmap.h>
#include <QCoreApplication>

#include "Localization.h"
#include "ImageEvent.h"
#include "LocationEvent.h"
#include "FailureEvent.h"

Localization::Localization() :
    _memory(NULL),
    _vis(NULL),
    _httpServer(NULL)
{
}

Localization::~Localization()
{
    if (_memory != NULL)
    {
        delete _memory;
    }
    _vis = NULL;
    _httpServer = NULL;
}

void Localization::setMemory(MemoryLoc *memory)
{
    _memory = memory;
}

void Localization::setVisibility(Visibility *vis)
{
    _vis = vis;
}

void Localization::setHTTPServer(HTTPServer *httpServer)
{
    _httpServer = httpServer;
}

bool Localization::event(QEvent *event)
{
    if (event->type() == ImageEvent::type())
    {
        ImageEvent *imageEvent = static_cast<ImageEvent *>(event);
        rtabmap::Transform pose = localize(imageEvent->sensorData());
        // a null pose notify that loc could not be computed
        if (!pose.isNull())
        {
            QCoreApplication::postEvent(_vis, new LocationEvent(imageEvent->sensorData(), pose, imageEvent->conInfo()));
        }
        else
        {
            QCoreApplication::postEvent(_httpServer, new FailureEvent(imageEvent->conInfo()));
        }
        return true;
    }
    return QObject::event(event);
}

rtabmap::Transform Localization::localize(rtabmap::SensorData *sensorData)
{
    UASSERT(!sensorData->imageRaw().empty());

    rtabmap::Transform output;

    const rtabmap::CameraModel &cameraModel = sensorData->cameraModels()[0];

    // generate kpts
    if (_memory->update(*sensorData))
    {
        UDEBUG("");
        const rtabmap::Signature *newS = _memory->getLastWorkingSignature();
        UDEBUG("newWords=%d", (int)newS->getWords().size());
        std::map<int, float> likelihood;
        std::list<int> signaturesToCompare = uKeysList(_memory->getSignatures());
        signaturesToCompare.remove(newS->id());
        UDEBUG("signaturesToCompare.size() = %d", signaturesToCompare.size());
        likelihood = _memory->computeLikelihood(newS, signaturesToCompare);

        std::vector<int> topIds;
        likelihood.erase(-1);
        int topId;
        if (likelihood.size())
        {
            std::vector< std::pair<int, float> > top(TOP_K);
            std::partial_sort_copy(likelihood.begin(),
                                   likelihood.end(),
                                   top.begin(),
                                   top.end(),
                                   compareLikelihood);
            for (std::vector< std::pair<int, float> >::iterator it = top.begin(); it != top.end(); ++it)
            {
                topIds.push_back(it->first);
            }
            topId = topIds[0];
            UDEBUG("topId: %d", topId);
        }

        sensorData->setId(newS->id());

        output = _memory->computeGlobalVisualTransform(topIds, sensorData->id());

        if (!output.isNull())
        {
            UDEBUG("global transform = %s", output.prettyPrint().c_str());
        }
        else
        {
            UWARN("transform is null, using pose of the closest image");
            //output = _memory->getOptimizedPose(topId);
        }

        // remove new words from dictionary
        _memory->deleteLocation(newS->id());
        _memory->emptyTrash();
    }

    UINFO("output transform = %s", output.prettyPrint().c_str());

    return output;
}

bool Localization::compareLikelihood(std::pair<const int, float> const &l, std::pair<const int, float> const &r)
{
    return l.second > r.second;
}