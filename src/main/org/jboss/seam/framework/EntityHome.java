package org.jboss.seam.framework;

import javax.persistence.EntityManager;
import javax.transaction.SystemException;

import org.jboss.seam.annotations.Transactional;
import org.jboss.seam.persistence.PersistenceProvider;
import org.jboss.seam.transaction.Transaction;

/**
 * Base class for Home objects of JPA entities.
 * 
 * @author Gavin King
 *
 */
public class EntityHome<E> extends Home<EntityManager, E>
{
   private static final long serialVersionUID = -3140094990727574632L;
   
   @Override
   public void create()
   {
      super.create();
      if ( getEntityManager()==null )
      {
         throw new IllegalStateException("entityManager is null");
      }
   }
   
   @Transactional
   public boolean isManaged()
   {
      return getInstance()!=null && 
            getEntityManager().contains( getInstance() );
   }

   @Transactional
   public String update()
   {
      joinTransaction();
      getEntityManager().flush();
      updatedMessage();
      raiseAfterTransactionSuccessEvent();
      return "updated";
   }
   
   @Transactional
   public String persist()
   {
      getEntityManager().persist( getInstance() );
      getEntityManager().flush();
      assignId( PersistenceProvider.instance().getId( getInstance(), getEntityManager() ) );
      createdMessage();
      raiseAfterTransactionSuccessEvent();
      return "persisted";
   }
   
   @Transactional
   public String remove()
   {
      getEntityManager().remove( getInstance() );
      getEntityManager().flush();
      deletedMessage();
      raiseAfterTransactionSuccessEvent();
      return "removed";
   }
   
    @Transactional
    @Override
    public E find()
    {
        if (getEntityManager().isOpen())  {
            E result = loadInstance();
            if (result==null) {
                result = handleNotFound();
            }
            return result;
        } else {
            return null;
        }
    }

    protected E loadInstance() 
    {
        return getEntityManager().find(getEntityClass(), getId());
    }

   @Override
   protected void joinTransaction()
   {
      if ( getEntityManager().isOpen() )
      {
         try
         {
            Transaction.instance().enlist( getEntityManager() );
         }
         catch (SystemException se)
         {
            throw new RuntimeException("could not join transaction", se);
         }
      }
   }
   
   public EntityManager getEntityManager()
   {
      return getPersistenceContext();
   }
   
   public void setEntityManager(EntityManager entityManager)
   {
      setPersistenceContext(entityManager);
   }
   
   @Override
   protected String getPersistenceContextName()
   {
      return "entityManager";
   }
   
   @Override
   protected String getEntityName()
   {
      try
      {
         return PersistenceProvider.instance().getName(getInstance(), getEntityManager());
      }
      catch (IllegalArgumentException e) 
      {
         // Handle that the passed object may not be an entity
         return null;
      }
   }
   
}
